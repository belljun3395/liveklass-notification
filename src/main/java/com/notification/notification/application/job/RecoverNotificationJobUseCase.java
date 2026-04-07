package com.notification.notification.application.job;

import com.notification.infra.port.DistributedLock;
import com.notification.notification.config.properties.NotificationProperties;
import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.domain.NotificationStatus;
import com.notification.notification.event.NotificationJobStatusChangedEvent;
import com.notification.notification.event.publisher.NotificationEventPublisher;
import com.notification.notification.repository.job.NotificationJobRepository;
import com.notification.notification.repository.notification.NotificationRepository;
import com.notification.support.web.exception.ResourceNotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실패하거나 취소된 알림 잡을 수동으로 복구하여 재발송을 준비합니다.
 *
 * <p>FAILED 또는 CANCELLED 상태의 잡만 복구할 수 있습니다. 그 외 상태에서는 {@link IllegalStateException}을 발생시킵니다.
 *
 * <p><b>처리 흐름:</b>
 *
 * <ol>
 *   <li>Job 조회 → 멱등키로 분산 락 획득 — 동시 요청 차단
 *   <li>{@code SELECT FOR UPDATE}로 Job 재조회 → {@code job.markRecovering()} (FAILED/CANCELLED →
 *       RETRYING)
 *   <li>Notification 리셋(DEAD_LETTER/CANCELLED → RETRY_WAITING) — Job 전이와 같은 트랜잭션에서 원자적 처리
 *   <li>{@code NotificationJobStatusChangedEvent} 발행 — 이력 기록
 * </ol>
 */
@Slf4j
@Component
public class RecoverNotificationJobUseCase {

    private final NotificationJobRepository jobRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationEventPublisher eventPublisher;
    private final DistributedLock distributedLock;
    private final TransactionTemplate transactionTemplate;
    private final Duration lockTtl;

    public RecoverNotificationJobUseCase(
            NotificationJobRepository jobRepository,
            NotificationRepository notificationRepository,
            NotificationEventPublisher eventPublisher,
            DistributedLock distributedLock,
            PlatformTransactionManager txManager,
            NotificationProperties properties) {
        this.jobRepository = jobRepository;
        this.notificationRepository = notificationRepository;
        this.eventPublisher = eventPublisher;
        this.distributedLock = distributedLock;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.lockTtl = properties.handler().executionIdempotencyTtl();
    }

    public void execute(Long jobId) {
        NotificationJob jobForKey =
                jobRepository
                        .findByIdAndDeletedFalse(jobId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Job not found: " + jobId));
        String idempotencyKey = jobForKey.getIdempotencyKey();

        Optional<String> lockToken = distributedLock.tryLock(idempotencyKey, lockTtl);
        if (lockToken.isEmpty()) {
            log.info("[UC:Recover] Lock held for job {}, skipping", jobId);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(
                    status -> {
                        NotificationJob job =
                                jobRepository
                                        .findByIdAndDeletedFalseForUpdate(jobId)
                                        .orElseThrow(
                                                () ->
                                                        new ResourceNotFoundException(
                                                                "Job not found: " + jobId));

                        JobStatus preStatus = job.getStatus();

                        if (!job.markRecovering()) {
                            throw new IllegalStateException(
                                    "Retry는 FAILED 또는 CANCELLED 상태의 잡에만 가능합니다. 현재 상태: "
                                            + preStatus);
                        }

                        List<Notification> targets = findResetTargets(jobId, preStatus);
                        for (Notification n : targets) {
                            job.resetNotificationForManualRetry(n);
                        }
                        notificationRepository.saveAll(targets);

                        String reason =
                                preStatus == JobStatus.FAILED
                                        ? "Retry failed notifications by user"
                                        : "Restore cancelled job by user";

                        eventPublisher.publish(
                                new NotificationJobStatusChangedEvent(
                                        jobId, preStatus, JobStatus.RETRYING, reason, "user"));

                        log.info(
                                "[UC:Recover] Job {} recovering ({} → RETRYING), {} notifications reset to RETRY_WAITING",
                                jobId,
                                preStatus,
                                targets.size());
                    });
        } finally {
            distributedLock.unlock(idempotencyKey, lockToken.get());
        }
    }

    private List<Notification> findResetTargets(Long jobId, JobStatus preStatus) {
        if (preStatus == JobStatus.FAILED) {
            return notificationRepository.findByJobIdAndStatusAndDeletedFalse(
                    jobId, NotificationStatus.DEAD_LETTER);
        }
        return notificationRepository.findByJobIdAndStatusAndDeletedFalse(
                jobId, NotificationStatus.CANCELLED);
    }
}
