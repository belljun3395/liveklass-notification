package com.notification.notification.domain;

import com.notification.infra.util.id.TsidId;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "notification_send_histories")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSendHistory {

    @TsidId @Id private Long id;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    // 하위 호환용 필드. toStatus와 항상 동일한 값을 가진다.
    // 마이그레이션 전 레거시 행 조회를 위해 유지되며, 신규 분석 쿼리는 toStatus를 사용한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationStatus status;

    @Column(name = "send_failure_reason", columnDefinition = "TEXT")
    private String sendFailureReason;

    @CreatedDate
    @Column(name = "sent_at", nullable = false, updatable = false)
    private OffsetDateTime sentAt;

    @Column(name = "attempt_no")
    private Integer attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private NotificationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 30)
    private NotificationStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 50)
    private FailureReasonCode failureCode;

    /** SENDING → SENT */
    public static NotificationSendHistory success(Long notificationId, int attemptNo) {
        NotificationSendHistory h = new NotificationSendHistory();
        h.notificationId = notificationId;
        h.status = NotificationStatus.SENT;
        h.fromStatus = NotificationStatus.SENDING;
        h.toStatus = NotificationStatus.SENT;
        h.attemptNo = attemptNo;
        return h;
    }

    /** SENDING → FAILED */
    public static NotificationSendHistory failure(
            Long notificationId,
            String sendFailureReason,
            int attemptNo,
            FailureReasonCode failureCode) {
        NotificationSendHistory h = new NotificationSendHistory();
        h.notificationId = notificationId;
        h.status = NotificationStatus.FAILED;
        h.sendFailureReason = sendFailureReason;
        h.fromStatus = NotificationStatus.SENDING;
        h.toStatus = NotificationStatus.FAILED;
        h.attemptNo = attemptNo;
        h.failureCode = failureCode;
        return h;
    }

    public static NotificationSendHistory deadLetterFromFailed(
            Long notificationId,
            String sendFailureReason,
            int attemptNo,
            FailureReasonCode failureCode) {
        Objects.requireNonNull(failureCode, "deadLetterFromFailed requires a non-null failureCode");
        NotificationSendHistory h = new NotificationSendHistory();
        h.notificationId = notificationId;
        h.status = NotificationStatus.DEAD_LETTER;
        h.sendFailureReason = sendFailureReason;
        h.fromStatus = NotificationStatus.FAILED;
        h.toStatus = NotificationStatus.DEAD_LETTER;
        h.attemptNo = attemptNo;
        h.failureCode = failureCode;
        return h;
    }

    /** SENDING → DEAD_LETTER (StuckProcessingRecoveryScheduler에서 사용) */
    public static NotificationSendHistory deadLetterFromSending(
            Long notificationId,
            String sendFailureReason,
            int attemptNo,
            FailureReasonCode failureCode) {
        Objects.requireNonNull(
                failureCode, "deadLetterFromSending requires a non-null failureCode");
        NotificationSendHistory h = new NotificationSendHistory();
        h.notificationId = notificationId;
        h.status = NotificationStatus.DEAD_LETTER;
        h.sendFailureReason = sendFailureReason;
        h.fromStatus = NotificationStatus.SENDING;
        h.toStatus = NotificationStatus.DEAD_LETTER;
        h.attemptNo = attemptNo;
        h.failureCode = failureCode;
        return h;
    }

    /** FAILED → RETRY_WAITING */
    public static NotificationSendHistory retryWaiting(
            Long notificationId, String sendFailureReason, int attemptNo) {
        NotificationSendHistory h = new NotificationSendHistory();
        h.notificationId = notificationId;
        h.status = NotificationStatus.RETRY_WAITING;
        h.sendFailureReason = sendFailureReason;
        h.fromStatus = NotificationStatus.FAILED;
        h.toStatus = NotificationStatus.RETRY_WAITING;
        h.attemptNo = attemptNo;
        // failureCode는 기록하지 않음 — FAILED 기록에서 이미 실패 원인 코드가 있음
        return h;
    }

    /** PENDING/RETRY_WAITING → SENDING */
    public static NotificationSendHistory startingSend(
            Long notificationId,
            String sendFailureReason,
            int attemptNo,
            NotificationStatus fromStatus) {
        Objects.requireNonNull(fromStatus, "fromStatus must not be null");
        NotificationSendHistory h = new NotificationSendHistory();
        h.notificationId = notificationId;
        h.status = NotificationStatus.SENDING;
        h.sendFailureReason = sendFailureReason; // 일반적으로 NULL (아직 발송 시도 전)
        h.fromStatus = fromStatus;
        h.toStatus = NotificationStatus.SENDING;
        h.attemptNo = attemptNo;
        // failureCode는 기록하지 않음 — SENDING은 진행 중 상태
        return h;
    }
}
