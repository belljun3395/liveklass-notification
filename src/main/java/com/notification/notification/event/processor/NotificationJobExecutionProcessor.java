package com.notification.notification.event.processor;

import com.notification.infra.port.DistributedLock;
import com.notification.notification.config.properties.NotificationProperties;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.event.NotificationJobExecutionEvent;
import com.notification.notification.repository.job.NotificationJobRepository;
import com.notification.notification.service.job.NotificationJobSendOrchestrator;
import com.notification.support.web.exception.ResourceNotFoundException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link NotificationJobExecutionEvent} 처리 로직.
 *
 * <p>이 클래스는 소비 방식(Spring Modulith, 브로커 Consumer 등)에 무관하게 재사용 가능합니다. 예를 들어 {@link
 * com.notification.notification.event.listener.spring.NotificationJobExecutionSpringAdapter}가 이벤트를
 * 수신하면 이 클래스의 {@link #process(NotificationJobExecutionEvent)}를 호출합니다.
 *
 * <p><b>Watchdog:</b> 발송 완료 전에 분산 락 TTL이 만료되면 다른 인스턴스가 동일 Job을 처리하게 됩니다. Watchdog은 {@code TTL / 3}
 * 주기로 락을 갱신하여 발송이 진행되는 동안 락을 유지합니다.
 */
@Slf4j
@Component
public class NotificationJobExecutionProcessor {

    private final NotificationJobRepository jobRepository;
    private final NotificationJobSendOrchestrator sendOrchestrator;
    private final DistributedLock distributedLock;
    private final Duration idempotencyTtl;
    private final ScheduledExecutorService watchdogExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "lock-watchdog");
                        t.setDaemon(true);
                        return t;
                    });

    public NotificationJobExecutionProcessor(
            NotificationJobRepository jobRepository,
            NotificationJobSendOrchestrator sendOrchestrator,
            DistributedLock distributedLock,
            NotificationProperties properties) {
        this.jobRepository = jobRepository;
        this.sendOrchestrator = sendOrchestrator;
        this.distributedLock = distributedLock;
        this.idempotencyTtl = properties.handler().executionIdempotencyTtl();
    }

    public void process(NotificationJobExecutionEvent event) {
        Optional<String> lockToken =
                distributedLock.tryLock(event.idempotencyKey(), idempotencyTtl);
        if (lockToken.isEmpty()) {
            log.info("[Handler:Execution] Duplicate event for job {}, skipping", event.jobId());
            return;
        }

        ScheduledFuture<?> watchdog = startWatchdog(event.idempotencyKey(), lockToken.get());
        try {
            NotificationJob job =
                    jobRepository
                            .findByIdAndDeletedFalseForUpdate(event.jobId())
                            .orElseThrow(
                                    () ->
                                            new ResourceNotFoundException(
                                                    "Job not found: " + event.jobId()));

            if (!job.getIdempotencyKey().equals(event.idempotencyKey())) {
                log.info(
                        "[Handler:Execution] Idempotency key mismatch for job {}, skipping",
                        event.jobId());
                return;
            }

            if (!job.isProcessing()) {
                log.warn(
                        "[Handler:Execution] Skipping job {}. Expected PROCESSING but was: {}",
                        job.getId(),
                        job.getStatus());
                return;
            }

            log.info(
                    "[Handler:Execution] Executing job {} (status={})",
                    job.getId(),
                    job.getStatus());

            sendOrchestrator.execute(job, "system:execution-handler");
        } finally {
            watchdog.cancel(false);
            distributedLock.unlock(event.idempotencyKey(), lockToken.get());
        }
    }

    private ScheduledFuture<?> startWatchdog(String lockName, String ownerToken) {
        long renewIntervalMs = idempotencyTtl.toMillis() / 3;
        return watchdogExecutor.scheduleAtFixedRate(
                () -> {
                    boolean renewed = distributedLock.renew(lockName, ownerToken, idempotencyTtl);
                    if (!renewed) {
                        log.warn("[Handler:Execution] Watchdog failed to renew lock: {}", lockName);
                    }
                },
                renewIntervalMs,
                renewIntervalMs,
                TimeUnit.MILLISECONDS);
    }
}
