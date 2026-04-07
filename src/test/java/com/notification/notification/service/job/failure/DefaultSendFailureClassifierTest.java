package com.notification.notification.service.job.failure;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.notification.config.properties.NotificationProperties;
import com.notification.notification.domain.FailureClassification;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationChannel;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.service.job.failure.SendFailureClassifier.ClassificationResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultSendFailureClassifier — 발송 실패 분류")
class DefaultSendFailureClassifierTest {

    private static final int MAX_SEND_TRY_COUNT = 3;

    private DefaultSendFailureClassifier classifier;

    @BeforeEach
    void setUp() {
        NotificationProperties properties =
                new NotificationProperties(
                        null,
                        null,
                        null,
                        new NotificationProperties.Retry(MAX_SEND_TRY_COUNT, null, null, 0.0),
                        null,
                        null,
                        null);
        classifier = new DefaultSendFailureClassifier(properties);
    }

    private Notification failedNotification(
            FailureClassification classification, int sendTryCount) {
        NotificationJob helper = createProcessingJob();
        Notification n = Notification.create(1L, 100L, "test@test.com", Map.of(), "TEST", Map.of());

        // sendTryCount만큼 발송 시도 시뮬레이션
        for (int i = 0; i < sendTryCount; i++) {
            helper.startSendingNotification(n);
            helper.failSendingNotification(n, classification, "test failure");
            if (i < sendTryCount - 1) {
                helper.recoverNotificationToRetryWaiting(n);
            }
        }
        return n;
    }

    private NotificationJob createProcessingJob() {
        NotificationJob job =
                NotificationJob.create(
                        NotificationChannel.EMAIL,
                        "title",
                        "content",
                        "key-" + System.nanoTime(),
                        "TEST",
                        Map.of());
        job.markScheduled();
        job.markProcessing();
        return job;
    }

    @Nested
    @DisplayName("PERMANENT 실패")
    class PermanentFailure {

        @Test
        @DisplayName("PERMANENT 분류는 sendTryCount와 무관하게 deadLetter로 분류된다")
        void permanent_always_dead_letter() {
            Notification n = failedNotification(FailureClassification.PERMANENT, 1);

            ClassificationResult result = classifier.classify(List.of(n));

            assertThat(result.deadLetters()).containsExactly(n);
            assertThat(result.retryable()).isEmpty();
        }
    }

    @Nested
    @DisplayName("TRANSIENT 실패")
    class TransientFailure {

        @Test
        @DisplayName("sendTryCount < maxSendTryCount → retryable")
        void transient_under_max_is_retryable() {
            Notification n = failedNotification(FailureClassification.TRANSIENT, 1);

            ClassificationResult result = classifier.classify(List.of(n));

            assertThat(result.retryable()).containsExactly(n);
            assertThat(result.deadLetters()).isEmpty();
            assertThat(result.hasRetryable()).isTrue();
        }

        @Test
        @DisplayName("sendTryCount == maxSendTryCount → deadLetter (재시도 소진)")
        void transient_at_max_is_dead_letter() {
            Notification n =
                    failedNotification(FailureClassification.TRANSIENT, MAX_SEND_TRY_COUNT);

            ClassificationResult result = classifier.classify(List.of(n));

            assertThat(result.deadLetters()).containsExactly(n);
            assertThat(result.retryable()).isEmpty();
        }

        @Test
        @DisplayName("sendTryCount > maxSendTryCount → deadLetter")
        void transient_over_max_is_dead_letter() {
            Notification n =
                    failedNotification(FailureClassification.TRANSIENT, MAX_SEND_TRY_COUNT + 1);

            ClassificationResult result = classifier.classify(List.of(n));

            assertThat(result.deadLetters()).containsExactly(n);
            assertThat(result.retryable()).isEmpty();
        }
    }

    @Nested
    @DisplayName("혼합 분류")
    class MixedClassification {

        @Test
        @DisplayName("PERMANENT + TRANSIENT(under max) 혼재 시 각각 분류된다")
        void mixed_permanent_and_retryable() {
            Notification permanent = failedNotification(FailureClassification.PERMANENT, 1);
            Notification retryable = failedNotification(FailureClassification.TRANSIENT, 1);

            ClassificationResult result = classifier.classify(List.of(permanent, retryable));

            assertThat(result.deadLetters()).containsExactly(permanent);
            assertThat(result.retryable()).containsExactly(retryable);
        }

        @Test
        @DisplayName("TRANSIENT(under max) + TRANSIENT(at max) 혼재 시 각각 분류된다")
        void mixed_retryable_and_exhausted() {
            Notification retryable = failedNotification(FailureClassification.TRANSIENT, 1);
            Notification exhausted =
                    failedNotification(FailureClassification.TRANSIENT, MAX_SEND_TRY_COUNT);

            ClassificationResult result = classifier.classify(List.of(retryable, exhausted));

            assertThat(result.retryable()).containsExactly(retryable);
            assertThat(result.deadLetters()).containsExactly(exhausted);
        }
    }

    @Nested
    @DisplayName("nextRetryAt 계산")
    class NextRetryAt {

        @Test
        @DisplayName("retryable이 없으면 nextRetryAt은 empty")
        void no_retryable_means_no_retry_at() {
            Notification permanent = failedNotification(FailureClassification.PERMANENT, 1);

            ClassificationResult result = classifier.classify(List.of(permanent));

            assertThat(result.nextRetryAt()).isEmpty();
        }

        @Test
        @DisplayName("retryable이 있으면 nextRetryAt이 present")
        void retryable_present_means_retry_at_present() {
            Notification retryable = failedNotification(FailureClassification.TRANSIENT, 1);

            ClassificationResult result = classifier.classify(List.of(retryable));

            assertThat(result.nextRetryAt()).isPresent();
        }

        @Test
        @DisplayName("retryable 중 최대 sendTryCount 기준으로 nextRetryAt이 계산된다")
        void retry_at_based_on_max_send_try_count() {
            Notification try1 = failedNotification(FailureClassification.TRANSIENT, 1);
            Notification try2 = failedNotification(FailureClassification.TRANSIENT, 2);

            ClassificationResult resultWith1 = classifier.classify(List.of(try1));
            ClassificationResult resultWith2 = classifier.classify(List.of(try1, try2));

            // try2가 포함되면 더 긴 백오프가 적용되어야 함
            assertThat(resultWith2.nextRetryAt().get()).isAfter(resultWith1.nextRetryAt().get());
        }
    }

    @Test
    @DisplayName("빈 목록 분류 시 모두 비어있다")
    void empty_list_returns_empty_result() {
        ClassificationResult result = classifier.classify(List.of());

        assertThat(result.deadLetters()).isEmpty();
        assertThat(result.retryable()).isEmpty();
        assertThat(result.nextRetryAt()).isEmpty();
    }
}
