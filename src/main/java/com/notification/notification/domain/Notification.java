package com.notification.notification.domain;

import com.notification.infra.util.id.TsidId;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
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
@Table(name = "notifications")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @TsidId @Id private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Column(name = "recipient_contact", nullable = false)
    private String recipientContact;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> variables;

    @Column(name = "notification_type", length = 50)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationStatus status;

    @Column(name = "send_try_count", nullable = false)
    private int sendTryCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_failure_classification", length = 20)
    private FailureClassification lastFailureClassification;

    @Column(name = "last_failure_reason", columnDefinition = "TEXT")
    private String lastFailureReason;

    @Column(name = "first_read_at")
    private OffsetDateTime firstReadAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(nullable = false)
    private boolean deleted;

    void markSending() {
        transitionTo(NotificationStatus.SENDING);
        this.sendTryCount++;
    }

    void markSent() {
        transitionTo(NotificationStatus.SENT);
        // 성공 시 이전 실패 정보 초기화
        this.lastFailureClassification = null;
        this.lastFailureReason = null;
    }

    void markFailed(FailureClassification classification, String failureReason) {
        transitionTo(NotificationStatus.FAILED);
        this.lastFailureClassification = classification;
        this.lastFailureReason = failureReason;
    }

    void markRetryWaiting() {
        transitionTo(NotificationStatus.RETRY_WAITING);
    }

    void markDeadLetter() {
        transitionTo(NotificationStatus.DEAD_LETTER);
        // lastFailureClassification, lastFailureReason은 이전 값 유지
        // (FAILED → DEAD_LETTER 전이 시 classification은 이미 설정됨)
    }

    void markDeadLetter(String failureReason) {
        transitionTo(NotificationStatus.DEAD_LETTER);
        // stuck recovery 경로: 실패 원인은 STUCK_TIMEOUT
        // classification을 TRANSIENT으로 설정 (stuck은 일시적 오류로 분류)
        this.lastFailureClassification = FailureClassification.TRANSIENT;
        this.lastFailureReason = failureReason;
    }

    void markCancelled() {
        transitionTo(NotificationStatus.CANCELLED);
    }

    void rollbackSendingToRetryWaiting() {
        transitionTo(NotificationStatus.RETRY_WAITING);
    }

    void resetForManualRetry() {
        transitionTo(NotificationStatus.RETRY_WAITING);
        this.sendTryCount = 0;
        this.lastFailureClassification = null;
        this.lastFailureReason = null;
    }

    private void transitionTo(NotificationStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Cannot transition from " + this.status + " to " + target);
        }
        this.status = target;
    }

    public static Notification create(
            Long jobId,
            Long recipientId,
            String recipientContact,
            Map<String, String> variables,
            String type,
            Map<String, Object> metadata) {
        Notification n = new Notification();
        n.jobId = jobId;
        n.recipientId = recipientId;
        n.recipientContact = recipientContact;
        n.variables = variables;
        n.type = type;
        n.metadata = metadata != null ? metadata : Map.of();
        n.status = NotificationStatus.PENDING;
        n.sendTryCount = 0;
        n.deleted = false;
        return n;
    }
}
