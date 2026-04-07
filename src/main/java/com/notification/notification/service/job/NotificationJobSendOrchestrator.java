package com.notification.notification.service.job;

import com.notification.notification.domain.FailureClassification;
import com.notification.notification.domain.FailureReasonCode;
import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.domain.NotificationSendHistory;
import com.notification.notification.domain.NotificationStatus;
import com.notification.notification.event.NotificationJobScheduleEvent;
import com.notification.notification.event.publisher.NotificationEventPublisher;
import com.notification.notification.repository.notification.NotificationRepository;
import com.notification.notification.repository.notification.NotificationSendHistoryRepository;
import com.notification.notification.service.job.failure.SendFailureClassifier;
import com.notification.notification.service.job.failure.SendFailureClassifier.ClassificationResult;
import com.notification.notification.service.job.send.NotificationJobSender;
import com.notification.notification.service.job.send.NotificationJobSender.FailedSend;
import com.notification.notification.service.job.send.NotificationJobSender.JobSendContext;
import com.notification.notification.service.job.send.NotificationJobSender.SendResult;
import com.notification.notification.service.sender.NotificationSendingStatePersister;
import io.micrometer.core.annotation.Timed;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 알림 발송 파이프라인을 조율하고 잡의 최종 상태를 결정합니다.
 *
 * <p><b>파이프라인:</b>
 *
 * <ol>
 *   <li>발송 대상 알림({@code PENDING}/{@code RETRY_WAITING}) 조회
 *   <li>알림 {@code SENDING} 전이 + 저장 — 발송 전 저장으로 크래시 시 복구 가능
 *   <li>채널 발송 실행
 *   <li>발송 결과({@code SENT}/{@code FAILED}) 반영 + 저장
 *   <li>실패 알림 분류({@code DEAD_LETTER}/{@code RETRY_WAITING}) 반영 + 저장
 *   <li>잡 최종 상태 결정 — {@code PROCESSING} → {@code COMPLETED} or {@code FAILED}
 * </ol>
 *
 * <p>모든 {@code NotificationJob} 상태 전이는 이 클래스에서만 수행됩니다.
 */
@Slf4j
@Component
public class NotificationJobSendOrchestrator {

    private final NotificationRepository notificationRepository;
    private final NotificationSendHistoryRepository sendHistoryRepository;
    private final NotificationJobSender sender;
    private final SendFailureClassifier classifier;
    private final NotificationEventPublisher eventPublisher;
    private final JobStatusHistoryRecorder historyRecorder;
    private final NotificationSendingStatePersister sendingStatePersister;

    public NotificationJobSendOrchestrator(
            NotificationRepository notificationRepository,
            NotificationSendHistoryRepository sendHistoryRepository,
            NotificationJobSender sender,
            SendFailureClassifier classifier,
            NotificationEventPublisher eventPublisher,
            JobStatusHistoryRecorder historyRecorder,
            NotificationSendingStatePersister sendingStatePersister) {
        this.notificationRepository = notificationRepository;
        this.sendHistoryRepository = sendHistoryRepository;
        this.sender = sender;
        this.classifier = classifier;
        this.eventPublisher = eventPublisher;
        this.historyRecorder = historyRecorder;
        this.sendingStatePersister = sendingStatePersister;
    }

    /**
     * 알림 잡에 속한 알림을 발송하고 파이프라인 전체를 실행합니다.
     *
     * @param job 발송할 알림 잡 (상태: {@code PROCESSING})
     * @param triggeredBy 실행 주체 식별자 (이력 기록용)
     */
    @Timed(
            value = "notification.batch.processing",
            description = "배치 처리 소요 시간",
            percentiles = {0.5, 0.95, 0.99})
    public void execute(NotificationJob job, String triggeredBy) {
        log.info(
                "[Orchestrator] Starting send for job {} (triggeredBy={})",
                job.getId(),
                triggeredBy);

        List<Notification> targets =
                notificationRepository.findByJobIdAndStatusInAndDeletedFalse(
                        job.getId(),
                        List.of(NotificationStatus.PENDING, NotificationStatus.RETRY_WAITING));

        if (targets.isEmpty()) {
            long sendingCount =
                    notificationRepository.countByJobIdAndStatusAndDeletedFalse(
                            job.getId(), NotificationStatus.SENDING);
            if (sendingCount > 0) {
                log.warn(
                        "[Orchestrator] Job {} has {} SENDING notification(s) but 0 PENDING/RETRY_WAITING"
                                + " — deferring to stuck recovery",
                        job.getId(),
                        sendingCount);
                return;
            }
        }

        log.info("[Orchestrator] Job {} — targets: {}", job.getId(), targets.size());

        List<NotificationSendHistory> startingHistories = new ArrayList<>();
        for (Notification n : targets) {
            NotificationStatus fromStatus = n.getStatus(); // PENDING 또는 RETRY_WAITING
            job.startSendingNotification(n);
            // PENDING/RETRY_WAITING → SENDING 전이 이력 기록
            // sendFailureReason은 NULL — 아직 발송이 시도되지 않음
            startingHistories.add(
                    NotificationSendHistory.startingSend(
                            n.getId(), null, n.getSendTryCount(), fromStatus));
        }
        // REQUIRES_NEW 트랜잭션으로 즉시 커밋 — 발송 중 크래시 시에도 SENDING 상태가 DB에 남아
        // StuckProcessingRecoveryScheduler가 감지하고 DEAD_LETTER 처리할 수 있습니다.
        sendingStatePersister.persist(targets);
        sendHistoryRepository.saveAll(startingHistories);

        JobSendContext context =
                new JobSendContext(
                        job.getChannel(), job.getTitleTemplate(), job.getContentTemplate());
        SendResult sendResult = sender.send(context, targets);

        applySendResults(job, sendResult);

        ClassificationResult classificationResult = null;
        if (sendResult.hasFailures()) {
            classificationResult = classifier.classify(sendResult.failedNotifications());
            applyClassificationResults(job, classificationResult);
        }

        resolveJobStatus(job, sendResult, classificationResult, triggeredBy);
    }

    private void applySendResults(NotificationJob job, SendResult sendResult) {
        List<Notification> all = new ArrayList<>();
        List<NotificationSendHistory> histories = new ArrayList<>();

        for (Notification n : sendResult.sent()) {
            job.completeSendingNotification(n);
            all.add(n);
            histories.add(NotificationSendHistory.success(n.getId(), n.getSendTryCount()));
        }
        for (FailedSend f : sendResult.failed()) {
            job.failSendingNotification(f.notification(), f.classification(), f.reason());
            all.add(f.notification());
            histories.add(
                    NotificationSendHistory.failure(
                            f.notification().getId(),
                            f.reason(),
                            f.notification().getSendTryCount(),
                            f.failureReasonCode()));
        }

        notificationRepository.saveAll(all);
        sendHistoryRepository.saveAll(histories);

        log.info(
                "[Orchestrator] Job {} — sent: {}, failed: {}",
                job.getId(),
                sendResult.sent().size(),
                sendResult.failed().size());
    }

    private void applyClassificationResults(NotificationJob job, ClassificationResult cr) {
        List<Notification> classified = new ArrayList<>();
        List<NotificationSendHistory> histories = new ArrayList<>();

        for (Notification n : cr.deadLetters()) {
            job.recoverNotificationToDeadLetter(n);
            classified.add(n);
            histories.add(
                    NotificationSendHistory.deadLetterFromFailed(
                            n.getId(),
                            n.getLastFailureReason(),
                            n.getSendTryCount(),
                            resolveDeadLetterCode(n)));
        }
        for (Notification n : cr.retryable()) {
            job.recoverNotificationToRetryWaiting(n);
            classified.add(n);
            histories.add(
                    NotificationSendHistory.retryWaiting(
                            n.getId(), n.getLastFailureReason(), n.getSendTryCount()));
        }

        notificationRepository.saveAll(classified);
        sendHistoryRepository.saveAll(histories);

        log.info(
                "[Orchestrator] Job {} — deadLetters: {}, retryable: {}",
                job.getId(),
                cr.deadLetters().size(),
                cr.retryable().size());
    }

    private void resolveJobStatus(
            NotificationJob job,
            SendResult sendResult,
            ClassificationResult classificationResult,
            String triggeredBy) {
        JobStatus preStatus = job.getStatus();
        boolean hasFailures = sendResult.hasFailures();
        boolean transitioned;
        String reason;

        if (!hasFailures) {
            transitioned = job.markCompleted();
            reason = "All notifications sent successfully";
        } else {
            transitioned = job.markFailed();
            reason =
                    String.format(
                            "Sending failed (deadLetters=%d, retryable=%d)",
                            classificationResult.deadLetters().size(),
                            classificationResult.retryable().size());
        }

        if (transitioned) {
            log.info(
                    "[Orchestrator] Job {} resolved: {} → {}",
                    job.getId(),
                    preStatus,
                    job.getStatus());
            historyRecorder.execute(job.getId(), preStatus, job.getStatus(), reason, triggeredBy);

            // 재시도 스케줄 등록은 이벤트로 위임하여 TX 커밋 후 별도 TX에서 수행한다.
            // TX 안에서 외부 서비스를 직접 호출하면 응답 대기 동안 행 잠금이 유지되고,
            // 외부 호출 성공 후 TX 롤백 시 DB-외부 서비스 간 불일치가 발생한다.
            if (hasFailures && classificationResult.hasRetryable()) {
                classificationResult
                        .nextRetryAt()
                        .ifPresent(
                                retryAt -> {
                                    eventPublisher.publish(
                                            NotificationJobScheduleEvent.retry(
                                                    job.getId(), retryAt));
                                    log.info(
                                            "[Orchestrator] Job {} — retry schedule event published (retryAt={})",
                                            job.getId(),
                                            retryAt);
                                });
            }
        } else {
            log.debug("[Orchestrator] Job {} no transition from {}", job.getId(), job.getStatus());
        }
    }

    private FailureReasonCode resolveDeadLetterCode(Notification n) {
        return n.getLastFailureClassification() == FailureClassification.PERMANENT
                ? FailureReasonCode.PERMANENT_FAILURE
                : FailureReasonCode.RETRY_EXHAUSTED;
    }
}
