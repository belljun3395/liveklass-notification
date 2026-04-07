package com.notification.notification.event.processor;

import com.notification.infra.port.DistributedLock;
import com.notification.notification.config.properties.NotificationProperties;
import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.event.NotificationJobCreatedAfterCommitEvent;
import com.notification.notification.event.NotificationJobScheduleEvent;
import com.notification.notification.event.publisher.NotificationEventPublisher;
import com.notification.notification.repository.job.NotificationJobRepository;
import com.notification.notification.service.job.JobStatusHistoryRecorder;
import com.notification.support.web.exception.ResourceNotFoundException;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link NotificationJobCreatedAfterCommitEvent} 처리 로직.
 *
 * <p>이 클래스는 소비 방식(Spring Modulith, 브로커 Consumer 등)에 무관하게 재사용 가능합니다. 예를 들어 {@link
 * com.notification.notification.event.listener.spring.NotificationJobCreatedSpringAdapter}가 이벤트를
 * 수신하면 이 클래스의 {@link #process(NotificationJobCreatedAfterCommitEvent)}를 호출합니다.
 */
@Slf4j
@Component
public class NotificationJobCreatedProcessor {

    private final NotificationJobRepository jobRepository;
    private final NotificationEventPublisher eventPublisher;
    private final JobStatusHistoryRecorder historyRecorder;
    private final DistributedLock distributedLock;
    private final Duration idempotencyTtl;

    public NotificationJobCreatedProcessor(
            NotificationJobRepository jobRepository,
            NotificationEventPublisher eventPublisher,
            JobStatusHistoryRecorder historyRecorder,
            DistributedLock distributedLock,
            NotificationProperties properties) {
        this.jobRepository = jobRepository;
        this.eventPublisher = eventPublisher;
        this.historyRecorder = historyRecorder;
        this.distributedLock = distributedLock;
        this.idempotencyTtl = properties.handler().createdIdempotencyTtl();
    }

    public void process(NotificationJobCreatedAfterCommitEvent event) {
        Optional<String> lockToken =
                distributedLock.tryLock(event.idempotencyKey(), idempotencyTtl);
        if (lockToken.isEmpty()) {
            log.info("[Handler:Created] Duplicate event for job {}, skipping", event.jobId());
            return;
        }

        try {
            NotificationJob job =
                    jobRepository
                            .findByIdAndDeletedFalse(event.jobId())
                            .orElseThrow(
                                    () ->
                                            new ResourceNotFoundException(
                                                    "Job not found: " + event.jobId()));

            if (!job.getIdempotencyKey().equals(event.idempotencyKey())) {
                log.info(
                        "[Handler:Created] Idempotency key mismatch for job {}, skipping",
                        event.jobId());
                return;
            }

            if (!job.markScheduled()) {
                log.warn(
                        "[Handler:Created] Cannot mark job {} as SCHEDULED. Current status: {}",
                        job.getId(),
                        job.getStatus());
                return;
            }

            eventPublisher.publish(
                    NotificationJobScheduleEvent.register(job.getId(), event.scheduledAt()));

            log.info(
                    "[Handler:Created] Job {} → SCHEDULED, scheduledAt={}",
                    job.getId(),
                    event.scheduledAt());

            historyRecorder.execute(
                    job.getId(), JobStatus.CREATED, JobStatus.SCHEDULED, "Job scheduled", "system");
        } finally {
            distributedLock.unlock(event.idempotencyKey(), lockToken.get());
        }
    }
}
