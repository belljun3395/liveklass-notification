package com.notification.notification.event.relay.db;

import com.notification.infra.port.DistributedLock;
import com.notification.notification.config.properties.NotificationProperties;
import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.domain.ScheduleType;
import com.notification.notification.domain.ScheduledNotificationJob;
import com.notification.notification.event.NotificationJobStatusChangedEvent;
import com.notification.notification.event.publisher.NotificationEventPublisher;
import com.notification.notification.repository.job.NotificationJobRepository;
import com.notification.notification.repository.job.ScheduledNotificationJobRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * DB에 저장된 스케줄 정보를 주기적으로 조회하여 실행 시간이 도달한 Job을 감지합니다. 조회한 알림 잡을 {@code SCHEDULED → PROCESSING} 상태로
 * 전이합니다.
 */
@Slf4j
@Component
public class DbScheduledNotificationJobRelay {

    private final ScheduledNotificationJobRepository scheduleRepository;
    private final NotificationJobRepository jobRepository;
    private final NotificationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final DistributedLock distributedLock;
    private final int batchSize;
    private final Duration lockTtl;

    public DbScheduledNotificationJobRelay(
            ScheduledNotificationJobRepository scheduleRepository,
            NotificationJobRepository jobRepository,
            NotificationEventPublisher eventPublisher,
            PlatformTransactionManager txManager,
            DistributedLock distributedLock,
            NotificationProperties properties) {
        this.scheduleRepository = scheduleRepository;
        this.jobRepository = jobRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.distributedLock = distributedLock;
        this.batchSize = properties.relay().schedule().batchSize();
        this.lockTtl = properties.handler().executionIdempotencyTtl();
    }

    @Scheduled(fixedDelayString = "${notification.relay.schedule.fixed-delay-ms:10000}")
    public void relay() {
        List<ScheduledNotificationJob> readyJobs =
                transactionTemplate.execute(
                        status ->
                                scheduleRepository
                                        .findByTypeAndExecutedFalseAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                                                ScheduleType.INITIAL,
                                                OffsetDateTime.now(ZoneOffset.UTC),
                                                PageRequest.of(0, batchSize)));

        if (readyJobs == null || readyJobs.isEmpty()) {
            return;
        }

        log.info("[Relay:Schedule] Found {} ready jobs to process", readyJobs.size());

        for (ScheduledNotificationJob scheduled : readyJobs) {
            try {
                processScheduledJob(scheduled);
            } catch (Exception e) {
                log.error(
                        "[Relay:Schedule] Failed to process schedule {} for job {}",
                        scheduled.getId(),
                        scheduled.getJobId(),
                        e);
                // relay error logged above
            }
        }
    }

    private void processScheduledJob(ScheduledNotificationJob scheduled) {
        NotificationJob jobForKey =
                jobRepository.findByIdAndDeletedFalse(scheduled.getJobId()).orElse(null);
        if (jobForKey == null) {
            log.warn(
                    "[Relay:Schedule] Job {} not found for schedule {}",
                    scheduled.getJobId(),
                    scheduled.getId());
            return;
        }
        String idempotencyKey = jobForKey.getIdempotencyKey();

        Optional<String> lockToken = distributedLock.tryLock(idempotencyKey, lockTtl);
        if (lockToken.isEmpty()) {
            log.debug(
                    "[Relay:Schedule] Job {} lock already held, skipping schedule {}",
                    scheduled.getJobId(),
                    scheduled.getId());
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(
                    status -> {
                        scheduled.markExecuted();
                        scheduleRepository.save(scheduled);

                        NotificationJob job =
                                jobRepository
                                        .findByIdAndDeletedFalseForUpdate(scheduled.getJobId())
                                        .orElse(null);
                        if (job == null) {
                            log.warn(
                                    "[Relay:Schedule] Job {} not found for schedule {}",
                                    scheduled.getJobId(),
                                    scheduled.getId());
                            return;
                        }

                        if (!job.getIdempotencyKey().equals(idempotencyKey)) {
                            log.info(
                                    "[Relay:Schedule] Idempotency key mismatch for job {}, skipping",
                                    job.getId());
                            return;
                        }

                        JobStatus preStatus = job.getStatus();
                        if (!job.markProcessing()) {
                            log.warn(
                                    "[Relay:Schedule] Cannot mark job {} as PROCESSING. Current status: {}",
                                    job.getId(),
                                    job.getStatus());
                            return;
                        }

                        log.info(
                                "[Relay:Schedule] Job {} transitioned {} → PROCESSING",
                                job.getId(),
                                preStatus);

                        eventPublisher.publish(
                                new NotificationJobStatusChangedEvent(
                                        job.getId(),
                                        preStatus,
                                        JobStatus.PROCESSING,
                                        "Scheduled time reached",
                                        "system:schedule-relay"));
                    });
        } finally {
            distributedLock.unlock(idempotencyKey, lockToken.get());
        }
    }
}
