package com.notification.notification.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.notification.domain.FailureClassification;
import com.notification.notification.domain.JobNotificationPolicy;
import com.notification.notification.domain.JobNotificationPolicy.NotificationTransition;
import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationChannel;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.domain.NotificationStatus;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * NotificationJob의 상태 전이 메서드 테스트.
 *
 * <p>모든 Notification 상태 전이는 반드시 NotificationJob의 메서드를 통해 수행됩니다. ({@code Notification}의 상태 변경 메서드는
 * package-private으로 직접 호출 불가)
 *
 * <p>런타임에서는 {@link JobNotificationPolicy}를 동적으로 조회하지 않고, {@link NotificationJob}이 필요한 enum 상수를
 * 명시적으로 사용합니다.
 */
@DisplayName("NotificationJob - Job이 Notification의 상태 전이를 제어한다")
class NotificationJobTest {

    // ── Fixtures ────────────────────────────────────────────────

    private NotificationJob newJob() {
        return NotificationJob.create(
                NotificationChannel.IN_APP,
                "제목 템플릿",
                "내용 템플릿",
                "key-" + System.nanoTime(),
                "SIGN_UP",
                Map.of());
    }

    private Notification newNotification() {
        return Notification.create(1L, 100L, "test@test.com", Map.of(), "SIGN_UP", Map.of());
    }

    private NotificationJob jobInProcessing() {
        NotificationJob job = newJob();
        job.markScheduled();
        job.markProcessing();
        return job;
    }

    private NotificationJob jobInCancelled() {
        NotificationJob job = newJob();
        job.markCancelled();
        return job;
    }

    private NotificationJob jobInRetrying() {
        NotificationJob job = newJob();
        job.markCancelled();
        job.markRetrying();
        return job;
    }

    private NotificationJob jobInState(JobStatus status) {
        return switch (status) {
            case PROCESSING -> jobInProcessing();
            case CANCELLED -> jobInCancelled();
            case RETRYING -> jobInRetrying();
            default -> throw new IllegalArgumentException("테스트 미지원 상태: " + status);
        };
    }

    /**
     * 원하는 NotificationStatus의 알림을 생성합니다.
     *
     * <p>setup 전용 job을 사용하여 NotificationJob 메서드만으로 목표 상태까지 체인을 구성합니다.
     */
    private Notification notificationAt(NotificationStatus target) {
        NotificationJob helper = jobInProcessing();
        Notification n = newNotification();

        return switch (target) {
            case PENDING -> n;
            case SENDING -> {
                helper.startSendingNotification(n);
                yield n;
            }
            case SENT -> {
                helper.startSendingNotification(n);
                helper.completeSendingNotification(n);
                yield n;
            }
            case FAILED -> {
                helper.startSendingNotification(n);
                helper.failSendingNotification(n, FailureClassification.TRANSIENT, "test");
                yield n;
            }
            case RETRY_WAITING -> {
                helper.startSendingNotification(n);
                helper.failSendingNotification(n, FailureClassification.TRANSIENT, "test");
                helper.recoverNotificationToRetryWaiting(n);
                yield n;
            }
            case DEAD_LETTER -> {
                helper.startSendingNotification(n);
                helper.failSendingNotification(n, FailureClassification.TRANSIENT, "test");
                helper.recoverNotificationToDeadLetter(n);
                yield n;
            }
            case CANCELLED -> {
                NotificationJob cancelHelper = jobInCancelled();
                Notification cn = newNotification();
                cancelHelper.cancelNotification(cn);
                yield cn;
            }
        };
    }

    /**
     * 정책의 to 상태에 대응하는 NotificationJob 메서드를 호출합니다.
     *
     * <p>{@code TO_RETRYING}의 RETRY_WAITING 복구는 런타임과 동일하게 {@code resetNotificationForManualRetry}를
     * 사용합니다.
     */
    private void performTransition(
            JobNotificationPolicy policy,
            NotificationJob job,
            Notification n,
            NotificationStatus to) {
        if (policy == JobNotificationPolicy.TO_RETRYING && to == NotificationStatus.RETRY_WAITING) {
            job.resetNotificationForManualRetry(n);
            return;
        }

        switch (to) {
            case SENDING -> job.startSendingNotification(n);
            case SENT -> job.completeSendingNotification(n);
            case FAILED -> job.failSendingNotification(n, FailureClassification.TRANSIENT, "test");
            case RETRY_WAITING -> job.recoverNotificationToRetryWaiting(n);
            case DEAD_LETTER -> job.recoverNotificationToDeadLetter(n);
            case CANCELLED -> job.cancelNotification(n);
            default -> throw new IllegalArgumentException("테스트 미지원 전이 대상: " + to);
        }
    }

    // ── 파라미터 소스 ─────────────────────────────────────────────

    static Stream<JobNotificationPolicy> explicitNotificationPolicies() {
        return Stream.of(
                JobNotificationPolicy.TO_PROCESSING,
                JobNotificationPolicy.TO_CANCELLED,
                JobNotificationPolicy.TO_RETRYING);
    }

    // ── 테스트 ───────────────────────────────────────────────────

    @Nested
    @DisplayName("명시적 정책 상수 기반 Notification 전이 제어")
    class PolicyBasedTransitionControl {

        @ParameterizedTest(name = "{0}: 허용된 전이가 모두 성공한다")
        @DisplayName("각 정책이 허용하는 모든 Notification 전이가 성공한다")
        @MethodSource(
                "com.notification.notification.domain.job.NotificationJobTest#explicitNotificationPolicies")
        void all_allowed_notification_transitions_of_policy_succeed(JobNotificationPolicy policy) {
            NotificationJob job = jobInState(policy.getToStatus());

            for (NotificationTransition transition : policy.getNotificationTransitions()) {
                Notification n = notificationAt(transition.from());

                performTransition(policy, job, n, transition.to());

                assertThat(n.getStatus())
                        .as(
                                "Policy %s: %s → %s 전이 후 상태",
                                policy, transition.from(), transition.to())
                        .isEqualTo(transition.to());
            }
        }

        @Test
        @DisplayName("정책에 없는 Notification 전이를 시도하면 예외를 던진다 (SENT → SENDING 불가)")
        void transition_not_in_policy_throws_illegal_state_exception() {
            NotificationJob processingJob = jobInProcessing();
            Notification sentNotification = notificationAt(NotificationStatus.SENT);

            assertThatThrownBy(() -> processingJob.startSendingNotification(sentNotification))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("허용되지 않습니다");
        }
    }

    @Nested
    @DisplayName("Job 상태 전이 메서드")
    class JobStatusTransitions {

        @Test
        @DisplayName("markScheduled: CREATED → SCHEDULED")
        void markScheduled_succeeds_from_created() {
            NotificationJob job = newJob();

            assertThat(job.markScheduled()).isTrue();
            assertThat(job.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        }

        @Test
        @DisplayName("markScheduled: SCHEDULED 상태에서는 실패한다 (멱등 아님)")
        void markScheduled_fails_if_not_created() {
            NotificationJob job = newJob();
            job.markScheduled();

            assertThat(job.markScheduled()).isFalse();
            assertThat(job.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        }

        @Test
        @DisplayName("markProcessing: SCHEDULED → PROCESSING")
        void markProcessing_succeeds_from_scheduled() {
            NotificationJob job = newJob();
            job.markScheduled();

            assertThat(job.markProcessing()).isTrue();
            assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
        }

        @Test
        @DisplayName("markProcessing: CREATED에서는 실패한다 (SCHEDULED를 거쳐야 함)")
        void markProcessing_fails_from_created() {
            NotificationJob job = newJob();

            assertThat(job.markProcessing()).isFalse();
            assertThat(job.getStatus()).isEqualTo(JobStatus.CREATED);
        }

        @Test
        @DisplayName("markCompleted: PROCESSING → COMPLETED")
        void markCompleted_succeeds_from_processing() {
            NotificationJob job = jobInProcessing();

            assertThat(job.markCompleted()).isTrue();
            assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        }

        @Test
        @DisplayName("markCompleted: COMPLETED는 terminal — 이후 어떤 전이도 불가하다")
        void markCompleted_is_terminal() {
            NotificationJob job = jobInProcessing();
            job.markCompleted();

            assertThat(job.markCancelled()).isFalse();
            assertThat(job.markRetrying()).isFalse();
            assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        }

        @Test
        @DisplayName("markFailed: PROCESSING → FAILED")
        void markFailed_succeeds_from_processing() {
            NotificationJob job = jobInProcessing();

            assertThat(job.markFailed()).isTrue();
            assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        }

        @Test
        @DisplayName("markCancelled: CREATED → CANCELLED")
        void markCancelled_succeeds_from_created() {
            NotificationJob job = newJob();

            assertThat(job.markCancelled()).isTrue();
            assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        }

        @Test
        @DisplayName("markRetrying: FAILED → RETRYING")
        void markRetrying_succeeds_from_failed() {
            NotificationJob job = jobInProcessing();
            job.markFailed();

            assertThat(job.markRetrying()).isTrue();
            assertThat(job.getStatus()).isEqualTo(JobStatus.RETRYING);
        }

        @Test
        @DisplayName("markRecovering: markRetrying의 별칭 — CANCELLED → RETRYING")
        void markRecovering_is_alias_of_markRetrying() {
            NotificationJob job = jobInCancelled();

            assertThat(job.markRecovering()).isTrue();
            assertThat(job.getStatus()).isEqualTo(JobStatus.RETRYING);
        }

        @Test
        @DisplayName("markStuckRecovery: PROCESSING → FAILED (STUCK_RECOVERY 정책)")
        void markStuckRecovery_transitions_processing_to_failed() {
            NotificationJob job = jobInProcessing();

            assertThat(job.markStuckRecovery()).isTrue();
            assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("Notification 상태 전이 제어 메서드")
    class NotificationTransitionMethods {

        @Test
        @DisplayName("startSendingNotification: PENDING → SENDING, sendTryCount 1 증가")
        void startSending_from_pending_increments_try_count() {
            NotificationJob job = jobInProcessing();
            Notification n = newNotification();

            job.startSendingNotification(n);

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENDING);
            assertThat(n.getSendTryCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("startSendingNotification: RETRY_WAITING → SENDING")
        void startSending_from_retry_waiting() {
            NotificationJob job = jobInProcessing();
            Notification n = notificationAt(NotificationStatus.RETRY_WAITING);

            job.startSendingNotification(n);

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENDING);
        }

        @Test
        @DisplayName("startSendingNotification: SENT 상태에서 호출하면 예외")
        void startSending_from_sent_throws() {
            NotificationJob job = jobInProcessing();
            Notification n = notificationAt(NotificationStatus.SENT);

            assertThatThrownBy(() -> job.startSendingNotification(n))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("completeSendingNotification: SENDING → SENT")
        void completeSending_transitions_to_sent() {
            NotificationJob job = jobInProcessing();
            Notification n = notificationAt(NotificationStatus.SENDING);

            job.completeSendingNotification(n);

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        }

        @Test
        @DisplayName("completeSendingNotification: PENDING 상태에서 호출하면 예외")
        void completeSending_from_pending_throws() {
            NotificationJob job = jobInProcessing();
            Notification n = newNotification();

            assertThatThrownBy(() -> job.completeSendingNotification(n))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("failSendingNotification: SENDING → FAILED, 분류와 사유 기록")
        void failSending_records_classification_and_reason() {
            NotificationJob job = jobInProcessing();
            Notification n = notificationAt(NotificationStatus.SENDING);

            job.failSendingNotification(n, FailureClassification.TRANSIENT, "network timeout");

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
            assertThat(n.getLastFailureClassification()).isEqualTo(FailureClassification.TRANSIENT);
            assertThat(n.getLastFailureReason()).isEqualTo("network timeout");
        }

        @Test
        @DisplayName("failStuckSendingNotification: SENDING → DEAD_LETTER (stuck 복구 전용)")
        void failStuckSending_transitions_to_dead_letter() {
            NotificationJob job = jobInProcessing();
            Notification n = notificationAt(NotificationStatus.SENDING);

            job.failStuckSendingNotification(n, "Stuck SENDING recovery");

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        }

        @Test
        @DisplayName("failStuckSendingNotification: SENDING이 아닌 상태에서 호출하면 예외")
        void failStuckSending_from_non_sending_throws() {
            NotificationJob job = jobInProcessing();
            Notification n = notificationAt(NotificationStatus.PENDING);

            assertThatThrownBy(() -> job.failStuckSendingNotification(n, "reason"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("cancelNotification: DEAD_LETTER → CANCELLED")
        void cancelNotification_from_dead_letter() {
            NotificationJob job = jobInCancelled();
            Notification n = notificationAt(NotificationStatus.DEAD_LETTER);

            job.cancelNotification(n);

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.CANCELLED);
        }

        @Test
        @DisplayName("cancelNotification: SENDING 상태에서 호출하면 예외 (진행 중인 발송은 취소 불가)")
        void cancelNotification_from_sending_throws() {
            NotificationJob job = jobInCancelled();
            Notification n = notificationAt(NotificationStatus.SENDING);

            assertThatThrownBy(() -> job.cancelNotification(n))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("cancelNotifications: 여러 알림을 일괄 취소한다")
        void cancelNotifications_cancels_all() {
            NotificationJob job = jobInCancelled();
            List<Notification> targets =
                    List.of(
                            notificationAt(NotificationStatus.PENDING),
                            notificationAt(NotificationStatus.RETRY_WAITING),
                            notificationAt(NotificationStatus.FAILED),
                            notificationAt(NotificationStatus.DEAD_LETTER));

            job.cancelNotifications(targets);

            assertThat(targets).allMatch(n -> n.getStatus() == NotificationStatus.CANCELLED);
        }

        @Test
        @DisplayName("recoverNotificationToRetryWaiting: FAILED → RETRY_WAITING")
        void recoverToRetryWaiting_from_failed() {
            NotificationJob job = jobInProcessing();
            Notification n = notificationAt(NotificationStatus.FAILED);

            job.recoverNotificationToRetryWaiting(n);

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.RETRY_WAITING);
        }

        @Test
        @DisplayName("recoverNotificationToDeadLetter: FAILED → DEAD_LETTER")
        void recoverToDeadLetter_from_failed() {
            NotificationJob job = jobInProcessing();
            Notification n = notificationAt(NotificationStatus.FAILED);

            job.recoverNotificationToDeadLetter(n);

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        }

        @Test
        @DisplayName(
                "resetNotificationForManualRetry: DEAD_LETTER → RETRY_WAITING, sendTryCount 0으로 초기화")
        void resetForManualRetry_from_dead_letter_resets_try_count() {
            NotificationJob job = jobInRetrying();
            Notification n = notificationAt(NotificationStatus.DEAD_LETTER);

            job.resetNotificationForManualRetry(n);

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.RETRY_WAITING);
            assertThat(n.getSendTryCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("resetNotificationForManualRetry: CANCELLED → RETRY_WAITING")
        void resetForManualRetry_from_cancelled() {
            NotificationJob job = jobInRetrying();
            Notification n = notificationAt(NotificationStatus.CANCELLED);

            job.resetNotificationForManualRetry(n);

            assertThat(n.getStatus()).isEqualTo(NotificationStatus.RETRY_WAITING);
        }

        @Test
        @DisplayName("resetNotificationForManualRetry: 수동 복구 시 실패 분류가 null로 초기화된다")
        void resetForManualRetry_clears_last_failure_classification() {
            NotificationJob job = jobInRetrying();
            Notification n = newNotification();

            // DEAD_LETTER 상태까지 전이
            NotificationJob processingJob = jobInProcessing();
            processingJob.startSendingNotification(n);
            processingJob.failSendingNotification(
                    n, FailureClassification.PERMANENT, "permanent error");
            processingJob.recoverNotificationToDeadLetter(n);

            // 수동 복구
            job.resetNotificationForManualRetry(n);

            assertThat(n.getLastFailureClassification()).isNull();
            assertThat(n.getLastFailureReason()).isNull();
            assertThat(n.getSendTryCount()).isZero();
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.RETRY_WAITING);
        }
    }
}
