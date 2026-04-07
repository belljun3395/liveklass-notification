package com.notification.notification.domain.job;

import static com.notification.notification.domain.JobStatus.CREATED;
import static com.notification.notification.domain.JobStatus.PROCESSING;
import static com.notification.notification.domain.JobStatus.RETRYING;
import static com.notification.notification.domain.JobStatus.SCHEDULED;
import static com.notification.notification.domain.NotificationStatus.DEAD_LETTER;
import static com.notification.notification.domain.NotificationStatus.PENDING;
import static com.notification.notification.domain.NotificationStatus.RETRY_WAITING;
import static com.notification.notification.domain.NotificationStatus.SENDING;
import static com.notification.notification.domain.NotificationStatus.SENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.notification.notification.domain.JobNotificationPolicy;
import com.notification.notification.domain.JobNotificationPolicy.NotificationTransition;
import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.NotificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JobNotificationPolicy - Job·Notification 상태 전이 정책")
class JobNotificationPolicyTest {

    @Nested
    @DisplayName("정합성 검증 - 정책 선언이 상태 머신 규칙과 일치해야 한다")
    class ConsistencyValidation {

        @Test
        @DisplayName("모든 정책의 Job 전이는 JobStatus의 허용 전이와 일치한다")
        void policy_job_transitions_match_job_status_allowed_transitions() {
            for (JobNotificationPolicy policy : JobNotificationPolicy.values()) {
                for (JobStatus from : policy.getFromStatuses()) {
                    if (from == policy.getToStatus()) {
                        // 자기 전이 정책(self-transition): Job 상태가 변경되지 않으므로 상태 머신 검증 대상에서 제외
                        continue;
                    }
                    assertThat(from.canTransitionTo(policy.getToStatus()))
                            .as(
                                    "정책 %s: Job %s → %s 전이가 허용되어야 합니다",
                                    policy, from, policy.getToStatus())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("모든 정책의 Notification 전이는 NotificationStatus의 허용 전이와 일치한다")
        void policy_notification_transitions_match_notification_status_allowed_transitions() {
            for (JobNotificationPolicy policy : JobNotificationPolicy.values()) {
                for (NotificationTransition t : policy.getNotificationTransitions()) {
                    assertThat(t.from().canTransitionTo(t.to()))
                            .as(
                                    "정책 %s: Notification %s → %s 전이가 허용되어야 합니다",
                                    policy, t.from(), t.to())
                            .isTrue();
                }
            }
        }
    }

    @Nested
    @DisplayName("정책 상수 테스트 - 각 정책의 Job·Notification 전이를 검증")
    class PolicyTests {

        @Test
        @DisplayName("TO_SCHEDULED: CREATED → SCHEDULED, Notification 영향 없음")
        void to_scheduled() {
            var policy = JobNotificationPolicy.TO_SCHEDULED;

            assertThat(policy.getFromStatuses()).containsExactlyInAnyOrder(CREATED);
            assertThat(policy.getToStatus()).isEqualTo(JobStatus.SCHEDULED);
            assertThat(policy.hasNotificationEffect()).isFalse();
        }

        @Test
        @DisplayName("TO_PROCESSING: SCHEDULED/RETRYING → PROCESSING, 발송 전 체인 전체 커버")
        void to_processing() {
            var policy = JobNotificationPolicy.TO_PROCESSING;

            assertThat(policy.getFromStatuses()).containsExactlyInAnyOrder(SCHEDULED, RETRYING);
            assertThat(policy.getToStatus()).isEqualTo(JobStatus.PROCESSING);
            assertThat(policy.getNotificationTransitions())
                    .containsExactlyInAnyOrder(
                            NotificationTransition.of(PENDING, SENDING),
                            NotificationTransition.of(RETRY_WAITING, SENDING),
                            NotificationTransition.of(SENDING, SENT),
                            NotificationTransition.of(SENDING, NotificationStatus.FAILED),
                            NotificationTransition.of(NotificationStatus.FAILED, RETRY_WAITING),
                            NotificationTransition.of(NotificationStatus.FAILED, DEAD_LETTER));
        }

        @Test
        @DisplayName(
                "TO_CANCELLED: CREATED/SCHEDULED/RETRYING → CANCELLED, 미발송 Notification만 취소 (SENDING 제외)")
        void to_cancelled() {
            var policy = JobNotificationPolicy.TO_CANCELLED;

            assertThat(policy.getFromStatuses())
                    .containsExactlyInAnyOrder(CREATED, SCHEDULED, RETRYING);
            assertThat(policy.getToStatus()).isEqualTo(JobStatus.CANCELLED);
            assertThat(policy.getNotificationTransitions())
                    .containsExactlyInAnyOrder(
                            NotificationTransition.of(PENDING, NotificationStatus.CANCELLED),
                            NotificationTransition.of(RETRY_WAITING, NotificationStatus.CANCELLED),
                            NotificationTransition.of(
                                    NotificationStatus.FAILED, NotificationStatus.CANCELLED),
                            NotificationTransition.of(DEAD_LETTER, NotificationStatus.CANCELLED));
        }

        @Test
        @DisplayName(
                "TO_RETRYING: FAILED/CANCELLED → RETRYING, DEAD_LETTER/CANCELLED Notification을 RETRY_WAITING으로 리셋")
        void to_retrying() {
            var policy = JobNotificationPolicy.TO_RETRYING;

            assertThat(policy.getFromStatuses())
                    .containsExactlyInAnyOrder(JobStatus.FAILED, JobStatus.CANCELLED);
            assertThat(policy.getToStatus()).isEqualTo(JobStatus.RETRYING);
            assertThat(policy.getNotificationTransitions())
                    .containsExactlyInAnyOrder(
                            NotificationTransition.of(DEAD_LETTER, RETRY_WAITING),
                            NotificationTransition.of(NotificationStatus.CANCELLED, RETRY_WAITING));
        }

        @Test
        @DisplayName("TO_COMPLETED: PROCESSING → COMPLETED, Notification 영향 없음 (모두 terminal 상태)")
        void to_completed() {
            var policy = JobNotificationPolicy.TO_COMPLETED;

            assertThat(policy.getFromStatuses()).containsExactlyInAnyOrder(PROCESSING);
            assertThat(policy.getToStatus()).isEqualTo(JobStatus.COMPLETED);
            assertThat(policy.hasNotificationEffect()).isFalse();
        }

        @Test
        @DisplayName(
                "TO_FAILED: PROCESSING → FAILED, Notification 영향 없음 (모두 terminal 또는 RETRY_WAITING)")
        void to_failed() {
            var policy = JobNotificationPolicy.TO_FAILED;

            assertThat(policy.getFromStatuses()).containsExactlyInAnyOrder(PROCESSING);
            assertThat(policy.getToStatus()).isEqualTo(JobStatus.FAILED);
            assertThat(policy.hasNotificationEffect()).isFalse();
        }

        @Test
        @DisplayName(
                "STUCK_RECOVERY: PROCESSING → FAILED, 잔류 SENDING Notification을 DEAD_LETTER로 전이")
        void stuck_recovery() {
            var policy = JobNotificationPolicy.STUCK_RECOVERY;

            assertThat(policy.getFromStatuses()).containsExactlyInAnyOrder(PROCESSING);
            assertThat(policy.getToStatus()).isEqualTo(JobStatus.FAILED);
            assertThat(policy.getNotificationTransitions())
                    .containsExactlyInAnyOrder(NotificationTransition.of(SENDING, DEAD_LETTER));
        }

        @Test
        @DisplayName(
                "STUCK_SENDING_ROLLBACK: PROCESSING → PROCESSING (self-transition), 잔류 SENDING Notification을 RETRY_WAITING으로 롤백")
        void stuck_sending_rollback() {
            var policy = JobNotificationPolicy.STUCK_SENDING_ROLLBACK;

            assertThat(policy.getFromStatuses()).containsExactlyInAnyOrder(PROCESSING);
            assertThat(policy.getToStatus()).isEqualTo(JobStatus.PROCESSING);
            assertThat(policy.hasNotificationEffect()).isTrue();
            assertThat(policy.getNotificationTransitions())
                    .containsExactlyInAnyOrder(NotificationTransition.of(SENDING, RETRY_WAITING));
        }
    }
}
