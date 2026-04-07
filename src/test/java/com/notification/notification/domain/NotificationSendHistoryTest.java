package com.notification.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NotificationSendHistory 팩토리 메서드")
class NotificationSendHistoryTest {

    @Test
    @DisplayName("success — SENDING→SENT, attemptNo 저장")
    void success_setsFields() {
        NotificationSendHistory h = NotificationSendHistory.success(1L, 2);

        assertThat(h.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(h.getFromStatus()).isEqualTo(NotificationStatus.SENDING);
        assertThat(h.getToStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(h.getAttemptNo()).isEqualTo(2);
        assertThat(h.getFailureCode()).isNull();
    }

    @Test
    @DisplayName("failure — SENDING→FAILED, failureCode 저장")
    void failure_setsFields() {
        NotificationSendHistory h =
                NotificationSendHistory.failure(
                        1L, "dispatch error", 1, FailureReasonCode.DISPATCHER_EXCEPTION);

        assertThat(h.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(h.getFromStatus()).isEqualTo(NotificationStatus.SENDING);
        assertThat(h.getToStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(h.getAttemptNo()).isEqualTo(1);
        assertThat(h.getFailureCode()).isEqualTo(FailureReasonCode.DISPATCHER_EXCEPTION);
    }

    @Test
    @DisplayName("failure — failureCode null 허용 (일반 TRANSIENT)")
    void failure_nullFailureCode_allowed() {
        NotificationSendHistory h = NotificationSendHistory.failure(1L, "transient", 1, null);

        assertThat(h.getFailureCode()).isNull();
    }

    @Test
    @DisplayName("deadLetterFromFailed — FAILED→DEAD_LETTER, failureCode 저장")
    void deadLetterFromFailed_setsFields() {
        NotificationSendHistory h =
                NotificationSendHistory.deadLetterFromFailed(
                        1L, "permanent", 3, FailureReasonCode.PERMANENT_FAILURE);

        assertThat(h.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(h.getFromStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(h.getToStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(h.getAttemptNo()).isEqualTo(3);
        assertThat(h.getFailureCode()).isEqualTo(FailureReasonCode.PERMANENT_FAILURE);
    }

    @Test
    @DisplayName("deadLetterFromSending — SENDING→DEAD_LETTER, STUCK_TIMEOUT 코드")
    void deadLetterFromSending_setsFields() {
        NotificationSendHistory h =
                NotificationSendHistory.deadLetterFromSending(
                        1L, "stuck timeout", 2, FailureReasonCode.STUCK_TIMEOUT);

        assertThat(h.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(h.getFromStatus()).isEqualTo(NotificationStatus.SENDING);
        assertThat(h.getToStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(h.getAttemptNo()).isEqualTo(2);
        assertThat(h.getFailureCode()).isEqualTo(FailureReasonCode.STUCK_TIMEOUT);
    }

    @Test
    @DisplayName("retryWaiting — FAILED→RETRY_WAITING, failureCode 없음")
    void retryWaiting_setsFields() {
        NotificationSendHistory h = NotificationSendHistory.retryWaiting(1L, "transient", 2);

        assertThat(h.getStatus()).isEqualTo(NotificationStatus.RETRY_WAITING);
        assertThat(h.getFromStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(h.getToStatus()).isEqualTo(NotificationStatus.RETRY_WAITING);
        assertThat(h.getAttemptNo()).isEqualTo(2);
        assertThat(h.getFailureCode()).isNull();
    }
}
