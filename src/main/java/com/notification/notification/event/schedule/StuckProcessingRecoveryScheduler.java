package com.notification.notification.event.schedule;

import com.notification.infra.port.DistributedLock;
import com.notification.notification.config.properties.NotificationProperties;
import com.notification.notification.domain.FailureReasonCode;
import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.domain.NotificationSendHistory;
import com.notification.notification.domain.NotificationStatus;
import com.notification.notification.event.NotificationJobStatusChangedEvent;
import com.notification.notification.event.publisher.NotificationEventPublisher;
import com.notification.notification.repository.job.NotificationJobRepository;
import com.notification.notification.repository.notification.NotificationRepository;
import com.notification.notification.repository.notification.NotificationSendHistoryRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 장시간 {@code PROCESSING} 상태에 머물러 있는 알림 잡을 감지합니다.
 *
 * <p>잔류 {@code SENDING} 알림에 대해 {@code sendTryCount < maxSendTryCount}이면 {@code RETRY_WAITING}으로
 * 롤백하고, 그렇지 않으면 {@code DEAD_LETTER}로 전이합니다. {@code RETRY_WAITING}으로 롤백된 알림이 존재하거나 {@code
 * PENDING}/{@code RETRY_WAITING} 알림이 잔존하면 잡을 {@code PROCESSING} 상태로 유지합니다. 처리 가능한 알림이 전혀 없을 때만 잡을
 * {@code PROCESSING → FAILED}로 전이합니다.
 */
@Slf4j
@Component
public class StuckProcessingRecoveryScheduler {

    private final NotificationJobRepository jobRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationSendHistoryRepository sendHistoryRepository;
    private final NotificationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final DistributedLock distributedLock;
    private final NotificationProperties properties;
    private final int batchSize;
    private final long stuckTimeoutSeconds;
    private final Duration lockTtl;

    public StuckProcessingRecoveryScheduler(
            NotificationJobRepository jobRepository,
            NotificationRepository notificationRepository,
            NotificationSendHistoryRepository sendHistoryRepository,
            NotificationEventPublisher eventPublisher,
            PlatformTransactionManager txManager,
            DistributedLock distributedLock,
            NotificationProperties properties) {
        this.jobRepository = jobRepository;
        this.notificationRepository = notificationRepository;
        this.sendHistoryRepository = sendHistoryRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.distributedLock = distributedLock;
        this.properties = properties;
        this.batchSize = properties.stuckRecovery().batchSize();
        this.stuckTimeoutSeconds = properties.stuckRecovery().stuckTimeoutSeconds();
        this.lockTtl = properties.handler().executionIdempotencyTtl();
    }

    @Scheduled(fixedDelayString = "${notification.stuck-recovery.fixed-delay-ms:60000}")
    public void recover() {
        OffsetDateTime cutoff =
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(stuckTimeoutSeconds);

        List<NotificationJob> stuckJobs =
                transactionTemplate.execute(
                        status ->
                                jobRepository.findByStatusAndDeletedFalseAndUpdatedAtBefore(
                                        JobStatus.PROCESSING,
                                        cutoff,
                                        PageRequest.of(0, batchSize)));

        if (stuckJobs == null || stuckJobs.isEmpty()) {
            return;
        }

        log.info(
                "[Recovery:StuckProcessing] Found {} stuck PROCESSING jobs (timeout={}s)",
                stuckJobs.size(),
                stuckTimeoutSeconds);

        for (NotificationJob job : stuckJobs) {
            try {
                recoverJob(job.getId(), job.getIdempotencyKey());
            } catch (Exception e) {
                log.error("[Recovery:StuckProcessing] Failed to recover job {}", job.getId(), e);
                // stuck-recovery error logged above
            }
        }
    }

    void recoverJob(Long jobId, String idempotencyKey) {
        Optional<String> lockToken = distributedLock.tryLock(idempotencyKey, lockTtl);
        if (lockToken.isEmpty()) {
            log.debug("[Recovery:StuckProcessing] Job {} lock already held, skipping", jobId);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(
                    status -> {
                        NotificationJob locked =
                                jobRepository.findByIdAndDeletedFalseForUpdate(jobId).orElse(null);
                        if (locked == null || locked.getStatus() != JobStatus.PROCESSING) {
                            return;
                        }

                        if (!locked.getIdempotencyKey().equals(idempotencyKey)) {
                            log.info(
                                    "[Recovery:StuckProcessing] Idempotency key mismatch for job {}, skipping",
                                    jobId);
                            return;
                        }

                        OffsetDateTime cutoff =
                                OffsetDateTime.now(ZoneOffset.UTC)
                                        .minusSeconds(stuckTimeoutSeconds);
                        if (!locked.getUpdatedAt().isBefore(cutoff)) {
                            return;
                        }

                        List<Notification> stuckSending =
                                notificationRepository.findByJobIdAndStatusAndDeletedFalse(
                                        locked.getId(), NotificationStatus.SENDING);

                        if (stuckSending.isEmpty()) {
                            // No stuck SENDING — check if there's active work remaining
                            long activeCount =
                                    notificationRepository.countByJobIdAndStatusInAndDeletedFalse(
                                            locked.getId(),
                                            List.of(
                                                    NotificationStatus.PENDING,
                                                    NotificationStatus.RETRY_WAITING));
                            if (activeCount > 0) {
                                log.debug(
                                        "[Recovery:StuckProcessing] Job {} has no stuck SENDING but {} active notification(s), skipping",
                                        jobId,
                                        activeCount);
                                return;
                            }
                            // Nothing left — mark FAILED
                            locked.markStuckRecovery();
                            eventPublisher.publish(
                                    new NotificationJobStatusChangedEvent(
                                            locked.getId(),
                                            JobStatus.PROCESSING,
                                            JobStatus.FAILED,
                                            "Auto-recovery: no active notifications in PROCESSING job",
                                            "system:stuck-recovery"));
                            return;
                        }

                        int maxSendTryCount = properties.retry().maxSendTryCount();
                        List<Notification> rolledBack = new ArrayList<>();
                        List<Notification> deadLettered = new ArrayList<>();
                        List<NotificationSendHistory> histories = new ArrayList<>();
                        String stuckReason =
                                "Stuck SENDING recovery (timeout=" + stuckTimeoutSeconds + "s)";

                        for (Notification n : stuckSending) {
                            if (n.getSendTryCount() < maxSendTryCount) {
                                locked.rollbackStuckSendingToRetryWaiting(n);
                                rolledBack.add(n);
                            } else {
                                locked.failStuckSendingNotification(n, stuckReason);
                                deadLettered.add(n);
                                histories.add(
                                        NotificationSendHistory.deadLetterFromSending(
                                                n.getId(),
                                                stuckReason,
                                                n.getSendTryCount(),
                                                FailureReasonCode.STUCK_TIMEOUT));
                            }
                        }

                        notificationRepository.saveAll(stuckSending);
                        if (!histories.isEmpty()) {
                            sendHistoryRepository.saveAll(histories);
                        }

                        if (!rolledBack.isEmpty()) {
                            // Rolled-back notifications will be retried — keep job PROCESSING

                            log.info(
                                    "[Recovery:StuckProcessing] Job {} rolled back {} SENDING → RETRY_WAITING, {} → DEAD_LETTER (job stays PROCESSING)",
                                    locked.getId(),
                                    rolledBack.size(),
                                    deadLettered.size());
                        } else {
                            // All stuck notifications dead-lettered — check for remaining active
                            long remainingActive =
                                    notificationRepository.countByJobIdAndStatusInAndDeletedFalse(
                                            locked.getId(),
                                            List.of(
                                                    NotificationStatus.PENDING,
                                                    NotificationStatus.RETRY_WAITING));
                            if (remainingActive == 0) {
                                locked.markStuckRecovery();
                                eventPublisher.publish(
                                        new NotificationJobStatusChangedEvent(
                                                locked.getId(),
                                                JobStatus.PROCESSING,
                                                JobStatus.FAILED,
                                                "Auto-recovery from stuck PROCESSING (timeout="
                                                        + stuckTimeoutSeconds
                                                        + "s, failedSending="
                                                        + deadLettered.size()
                                                        + ")",
                                                "system:stuck-recovery"));

                                log.info(
                                        "[Recovery:StuckProcessing] Job {} recovered (PROCESSING → FAILED, failedSending={})",
                                        locked.getId(),
                                        deadLettered.size());
                            } else {

                                log.info(
                                        "[Recovery:StuckProcessing] Job {} all {} stuck SENDING → DEAD_LETTER but {} active remain, job stays PROCESSING",
                                        locked.getId(),
                                        deadLettered.size(),
                                        remainingActive);
                            }
                        }
                    });
        } finally {
            distributedLock.unlock(idempotencyKey, lockToken.get());
        }
    }
}
