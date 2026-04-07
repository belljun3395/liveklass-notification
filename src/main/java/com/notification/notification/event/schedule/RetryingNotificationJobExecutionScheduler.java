package com.notification.notification.event.schedule;

import com.notification.infra.port.DistributedLock;
import com.notification.notification.config.properties.NotificationProperties;
import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.event.NotificationJobExecutionEvent;
import com.notification.notification.event.NotificationJobStatusChangedEvent;
import com.notification.notification.event.publisher.NotificationEventPublisher;
import com.notification.notification.repository.job.NotificationJobRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** {@code RETRYING} 상태의 알림 잡을 주기적으로 폴링하여 {@code RETRYING → PROCESSING} 전이 후 실행 이벤트를 발행합니다. */
@Slf4j
@Component
public class RetryingNotificationJobExecutionScheduler {

    private final NotificationJobRepository jobRepository;
    private final NotificationEventPublisher eventPublisher;
    private final DistributedLock distributedLock;
    private final TransactionTemplate transactionTemplate;
    private final Duration idempotencyTtl;
    private final int batchSize;

    public RetryingNotificationJobExecutionScheduler(
            NotificationJobRepository jobRepository,
            NotificationEventPublisher eventPublisher,
            DistributedLock distributedLock,
            PlatformTransactionManager txManager,
            NotificationProperties properties) {
        this.jobRepository = jobRepository;
        this.eventPublisher = eventPublisher;
        this.distributedLock = distributedLock;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.idempotencyTtl = properties.handler().executionIdempotencyTtl();
        this.batchSize = properties.relay().retrying().batchSize();
    }

    @Scheduled(fixedDelayString = "${notification.relay.retrying.fixed-delay-ms:5000}")
    public void relay() {
        List<NotificationJob> retryingJobs =
                transactionTemplate.execute(
                        status ->
                                jobRepository.findByStatusAndDeletedFalse(
                                        JobStatus.RETRYING, PageRequest.of(0, batchSize)));

        if (retryingJobs == null || retryingJobs.isEmpty()) {
            return;
        }

        log.info(
                "[Relay:Retrying] Found {} RETRYING jobs to transition to PROCESSING",
                retryingJobs.size());

        for (NotificationJob job : retryingJobs) {
            try {
                transitionAndPublish(job.getId(), job.getIdempotencyKey());
            } catch (Exception e) {
                log.error("[Relay:Retrying] Failed to process RETRYING job {}", job.getId(), e);
            }
        }
    }

    private void transitionAndPublish(Long jobId, String idempotencyKey) {
        Optional<String> lockToken = distributedLock.tryLock(idempotencyKey, idempotencyTtl);
        if (lockToken.isEmpty()) {
            log.debug("[Relay:Retrying] Job {} lock already held, skipping", jobId);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(
                    status -> {
                        NotificationJob job =
                                jobRepository.findByIdAndDeletedFalseForUpdate(jobId).orElse(null);
                        if (job == null) {
                            log.warn("[Relay:Retrying] Job {} not found, skipping", jobId);
                            return;
                        }

                        if (!job.getIdempotencyKey().equals(idempotencyKey)) {
                            log.info(
                                    "[Relay:Retrying] Idempotency key mismatch for job {}, skipping",
                                    jobId);
                            return;
                        }

                        if (job.getStatus() != JobStatus.RETRYING) {
                            log.debug(
                                    "[Relay:Retrying] Job {} is no longer RETRYING (status={}), skipping",
                                    jobId,
                                    job.getStatus());
                            return;
                        }

                        if (!job.markProcessing()) {
                            log.warn(
                                    "[Relay:Retrying] Cannot mark job {} as PROCESSING from RETRYING",
                                    jobId);
                            return;
                        }

                        eventPublisher.publish(
                                new NotificationJobStatusChangedEvent(
                                        job.getId(),
                                        JobStatus.RETRYING,
                                        JobStatus.PROCESSING,
                                        "Retrying scheduler triggered",
                                        "system:retrying-scheduler"));

                        eventPublisher.publish(
                                new NotificationJobExecutionEvent(
                                        job.getId(),
                                        job.getIdempotencyKey(),
                                        "Retrying job execution"));

                        log.info(
                                "[Relay:Retrying] Job {} transitioned (RETRYING → PROCESSING), execution scheduled",
                                job.getId());
                    });
        } finally {
            distributedLock.unlock(idempotencyKey, lockToken.get());
        }
    }
}
