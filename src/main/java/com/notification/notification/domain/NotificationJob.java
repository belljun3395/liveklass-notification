package com.notification.notification.domain;

import com.notification.infra.util.id.TsidId;
import com.notification.notification.domain.JobNotificationPolicy.NotificationTransition;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "notification_jobs")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationJob {

    @TsidId @Id private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobStatus status;

    @Column(name = "title_template", nullable = false, length = 500)
    private String titleTemplate;

    @Column(name = "content_template", nullable = false, columnDefinition = "TEXT")
    private String contentTemplate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "notification_type", length = 50)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(nullable = false)
    private boolean deleted;

    // ── Job 상태 전이 ────────────────────────────────────────────

    public boolean markCompleted() {
        return transitionTo(JobNotificationPolicy.TO_COMPLETED);
    }

    public boolean markFailed() {
        return transitionTo(JobNotificationPolicy.TO_FAILED);
    }

    public boolean markCancelled() {
        return transitionTo(JobNotificationPolicy.TO_CANCELLED);
    }

    public boolean markScheduled() {
        return transitionTo(JobNotificationPolicy.TO_SCHEDULED);
    }

    public boolean markProcessing() {
        return transitionTo(JobNotificationPolicy.TO_PROCESSING);
    }

    public boolean markRetrying() {
        return transitionTo(JobNotificationPolicy.TO_RETRYING);
    }

    /** 수동 복구 진입. FAILED/CANCELLED → RETRYING. */
    public boolean markRecovering() {
        return transitionTo(JobNotificationPolicy.TO_RETRYING);
    }

    /** 스턱 PROCESSING 복구. PROCESSING → FAILED ({@code STUCK_RECOVERY} 정책). */
    public boolean markStuckRecovery() {
        return transitionTo(JobNotificationPolicy.STUCK_RECOVERY);
    }

    public boolean isProcessing() {
        return this.status == JobStatus.PROCESSING;
    }

    // ── Notification 상태 전이 제어 ──────────────────────────────

    public void startSendingNotification(Notification n) {
        assertNotificationTransitionAllowed(
                JobNotificationPolicy.TO_PROCESSING, n.getStatus(), NotificationStatus.SENDING);
        n.markSending();
    }

    public void completeSendingNotification(Notification n) {
        assertNotificationTransitionAllowed(
                JobNotificationPolicy.TO_PROCESSING, n.getStatus(), NotificationStatus.SENT);
        n.markSent();
    }

    public void failSendingNotification(
            Notification n, FailureClassification classification, String failureReason) {
        assertNotificationTransitionAllowed(
                JobNotificationPolicy.TO_PROCESSING, n.getStatus(), NotificationStatus.FAILED);
        n.markFailed(classification, failureReason);
    }

    public void failStuckSendingNotification(Notification n, String failureReason) {
        assertNotificationTransitionAllowed(
                JobNotificationPolicy.STUCK_RECOVERY,
                n.getStatus(),
                NotificationStatus.DEAD_LETTER);
        n.markDeadLetter(failureReason);
    }

    public void rollbackStuckSendingToRetryWaiting(Notification n) {
        assertNotificationTransitionAllowed(
                JobNotificationPolicy.STUCK_SENDING_ROLLBACK,
                n.getStatus(),
                NotificationStatus.RETRY_WAITING);
        n.rollbackSendingToRetryWaiting();
    }

    public void cancelNotification(Notification n) {
        assertNotificationTransitionAllowed(
                JobNotificationPolicy.TO_CANCELLED, n.getStatus(), NotificationStatus.CANCELLED);
        n.markCancelled();
    }

    public void cancelNotifications(List<Notification> notifications) {
        for (Notification n : notifications) {
            cancelNotification(n);
        }
    }

    public void recoverNotificationToRetryWaiting(Notification n) {
        assertNotificationTransitionAllowed(
                JobNotificationPolicy.TO_PROCESSING,
                n.getStatus(),
                NotificationStatus.RETRY_WAITING);
        n.markRetryWaiting();
    }

    public void recoverNotificationToDeadLetter(Notification n) {
        assertNotificationTransitionAllowed(
                JobNotificationPolicy.TO_PROCESSING, n.getStatus(), NotificationStatus.DEAD_LETTER);
        n.markDeadLetter();
    }

    public void resetNotificationForManualRetry(Notification n) {
        assertNotificationTransitionAllowed(
                JobNotificationPolicy.TO_RETRYING, n.getStatus(), NotificationStatus.RETRY_WAITING);
        n.resetForManualRetry();
    }

    // ── Private helpers ─────────────────────────────────────────

    private boolean transitionTo(JobNotificationPolicy policy) {
        if (!policy.getFromStatuses().contains(this.status)) {
            return false;
        }
        this.status = policy.getToStatus();
        return true;
    }

    private void assertNotificationTransitionAllowed(
            JobNotificationPolicy policy, NotificationStatus from, NotificationStatus to) {
        if (!policy.getNotificationTransitions().contains(NotificationTransition.of(from, to))) {
            throw new IllegalStateException(
                    String.format(
                            "Notification %s → %s 전이는 Policy %s에서 허용되지 않습니다", from, to, policy));
        }
    }

    public static NotificationJob create(
            NotificationChannel channel,
            String titleTemplate,
            String contentTemplate,
            String idempotencyKey,
            String type,
            Map<String, Object> metadata) {
        NotificationJob job = new NotificationJob();
        job.channel = channel;
        job.titleTemplate = titleTemplate;
        job.contentTemplate = contentTemplate;
        job.idempotencyKey = idempotencyKey;
        job.type = type;
        job.metadata = metadata != null ? metadata : Map.of();
        job.status = JobStatus.CREATED;
        job.deleted = false;
        return job;
    }
}
