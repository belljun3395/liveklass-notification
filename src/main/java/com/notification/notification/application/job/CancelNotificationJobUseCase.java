package com.notification.notification.application.job;

import com.notification.infra.port.DistributedLock;
import com.notification.notification.config.properties.NotificationProperties;
import com.notification.notification.domain.JobNotificationPolicy;
import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.domain.NotificationStatus;
import com.notification.notification.event.NotificationJobScheduleEvent;
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
 * 알림 잡과 대기 중인 알림을 취소하고 등록된 스케줄을 해제합니다.
 *
 * <p>발송이 시작되지 않은 상태(CREATED, SCHEDULED, RETRYING)에서만 취소할 수 있습니다. 실행 중이거나 종료된 잡(PROCESSING,
 * COMPLETED, FAILED)은 취소할 수 없으며 {@link IllegalStateException}을 발생시킵니다. 이미 CANCELLED인 경우 멱등하게 처리됩니다.
 *
 * <p><b>처리 흐름:</b>
 *
 * <ol>
 *   <li>Job 조회 → 멱등키로 분산 락 획득 — 동시 요청 차단
 *   <li>{@code SELECT FOR UPDATE}로 Job 재조회 → {@code job.markCancelled()} 상태 전이
 *   <li>{@code NotificationJobStatusChangedEvent} 발행
 *   <li>대기 중인 Notification 일괄 취소(PENDING/RETRY_WAITING/FAILED/DEAD_LETTER → CANCELLED)
 *   <li>예약/재시도 스케줄 해제
 * </ol>
 *
 * <p>SENDING 상태 Notification은 이미 외부 채널에 발송 요청이 나간 상태이므로 취소 대상에서 제외됩니다.
 */
@Slf4j
@Component
public class CancelNotificationJobUseCase {

    private static final List<NotificationStatus> CANCELLABLE_NOTIFICATION_STATUSES =
            JobNotificationPolicy.TO_CANCELLED.getNotificationTransitions().stream()
                    .map(JobNotificationPolicy.NotificationTransition::from)
                    .distinct()
                    .toList();

    private final NotificationJobRepository jobRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationEventPublisher eventPublisher;
    private final DistributedLock distributedLock;
    private final TransactionTemplate transactionTemplate;
    private final Duration lockTtl;

    public CancelNotificationJobUseCase(
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
            log.info("[UC:Cancel] Lock held for job {}, skipping", jobId);
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

                        if (!job.markCancelled()) {
                            if (preStatus.equals(JobStatus.CANCELLED)) {
                                return;
                            }
                            throw new IllegalStateException("취소 불가 상태입니다. 현재 상태: " + preStatus);
                        }

                        eventPublisher.publish(
                                new NotificationJobStatusChangedEvent(
                                        jobId,
                                        preStatus,
                                        JobStatus.CANCELLED,
                                        "Cancel Notification by User",
                                        "user"));

                        List<Notification> targets =
                                notificationRepository.findByJobIdAndStatusInAndDeletedFalse(
                                        jobId, CANCELLABLE_NOTIFICATION_STATUSES);
                        job.cancelNotifications(targets);
                        notificationRepository.saveAll(targets);

                        eventPublisher.publish(NotificationJobScheduleEvent.cancel(jobId));

                        log.info(
                                "[UC:Cancel] Job {} cancelled ({} → CANCELLED, {} notifications cancelled)",
                                jobId,
                                preStatus,
                                targets.size());
                    });
        } finally {
            distributedLock.unlock(idempotencyKey, lockToken.get());
        }
    }
}
