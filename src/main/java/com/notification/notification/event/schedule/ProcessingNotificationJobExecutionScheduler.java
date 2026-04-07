package com.notification.notification.event.schedule;

import com.notification.notification.config.properties.NotificationProperties;
import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.event.NotificationJobExecutionEvent;
import com.notification.notification.event.publisher.NotificationEventPublisher;
import com.notification.notification.repository.job.NotificationJobRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** {@code PROCESSING} 상태의 알림 잡을 주기적으로 폴링하여 실행 이벤트를 발행합니다. */
@Slf4j
@Component
public class ProcessingNotificationJobExecutionScheduler {

    private final NotificationJobRepository jobRepository;
    private final NotificationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;

    public ProcessingNotificationJobExecutionScheduler(
            NotificationJobRepository jobRepository,
            NotificationEventPublisher eventPublisher,
            PlatformTransactionManager txManager,
            NotificationProperties properties) {
        this.jobRepository = jobRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.batchSize = properties.relay().execution().batchSize();
    }

    @Scheduled(fixedDelayString = "${notification.relay.execution.fixed-delay-ms:5000}")
    public void schedule() {
        List<NotificationJob> processingJobs =
                transactionTemplate.execute(
                        status ->
                                jobRepository.findByStatusAndDeletedFalse(
                                        JobStatus.PROCESSING, PageRequest.of(0, batchSize)));

        if (processingJobs == null || processingJobs.isEmpty()) {
            return;
        }

        log.info(
                "[Relay:Execution] Found {} PROCESSING jobs to publish execution events",
                processingJobs.size());

        for (NotificationJob job : processingJobs) {
            try {
                transactionTemplate.executeWithoutResult(
                        status ->
                                eventPublisher.publish(
                                        new NotificationJobExecutionEvent(
                                                job.getId(),
                                                job.getIdempotencyKey(),
                                                "Processing job execution relay")));

                log.debug(
                        "[Relay:Execution] Published execution event for PROCESSING job {}",
                        job.getId());
            } catch (Exception e) {
                log.error(
                        "[Relay:Execution] Failed to publish execution event for job {}",
                        job.getId(),
                        e);
            }
        }
    }
}
