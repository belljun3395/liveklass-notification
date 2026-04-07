package com.notification.notification.event.schedule;

import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.domain.NotificationStatus;
import com.notification.notification.repository.job.NotificationJobRepository;
import com.notification.notification.repository.notification.NotificationRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 서버 재시작 후 잔류 {@code SENDING} 알림을 {@code RETRY_WAITING}으로 롤백합니다.
 *
 * <p>크래시로 인해 {@code PROCESSING} 잡에 {@code SENDING} 상태 알림이 남아 있을 수 있습니다. 애플리케이션이 준비된 직후 한 번만 실행되며,
 * 이후 {@link ProcessingNotificationJobExecutionScheduler}가 정상 재처리를 이어받습니다.
 */
@Slf4j
@Component
public class NotificationJobRestartReconciler {

    private static final int BATCH_SIZE = 100;

    private final NotificationJobRepository jobRepository;
    private final NotificationRepository notificationRepository;
    private final TransactionTemplate transactionTemplate;

    public NotificationJobRestartReconciler(
            NotificationJobRepository jobRepository,
            NotificationRepository notificationRepository,
            PlatformTransactionManager txManager) {
        this.jobRepository = jobRepository;
        this.notificationRepository = notificationRepository;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        List<NotificationJob> processingJobs =
                transactionTemplate.execute(
                        status ->
                                jobRepository.findByStatusAndDeletedFalse(
                                        JobStatus.PROCESSING, PageRequest.of(0, BATCH_SIZE)));

        if (processingJobs == null || processingJobs.isEmpty()) {
            return;
        }

        log.info(
                "[RestartReconciler] Found {} PROCESSING job(s) on startup — checking for stale SENDING notifications",
                processingJobs.size());

        int totalRolledBack = 0;
        for (NotificationJob job : processingJobs) {
            try {
                int rolledBack = reconcileJob(job.getId());
                totalRolledBack += rolledBack;
            } catch (Exception e) {
                log.error("[RestartReconciler] Failed to reconcile job {}", job.getId(), e);
            }
        }

        if (totalRolledBack > 0) {
            log.info(
                    "[RestartReconciler] Reconciliation complete — {} notification(s) rolled back",
                    totalRolledBack);
        }
    }

    private int reconcileJob(Long jobId) {
        Integer rolledBack =
                transactionTemplate.execute(
                        status -> {
                            NotificationJob job =
                                    jobRepository
                                            .findByIdAndDeletedFalseForUpdate(jobId)
                                            .orElse(null);
                            if (job == null || job.getStatus() != JobStatus.PROCESSING) {
                                return 0;
                            }

                            List<Notification> stuckSending =
                                    notificationRepository.findByJobIdAndStatusAndDeletedFalse(
                                            job.getId(), NotificationStatus.SENDING);

                            if (stuckSending.isEmpty()) {
                                return 0;
                            }

                            for (Notification n : stuckSending) {
                                job.rollbackStuckSendingToRetryWaiting(n);
                            }
                            notificationRepository.saveAll(stuckSending);

                            log.info(
                                    "[RestartReconciler] Job {} — rolled back {} SENDING → RETRY_WAITING",
                                    job.getId(),
                                    stuckSending.size());

                            return stuckSending.size();
                        });
        return rolledBack != null ? rolledBack : 0;
    }
}
