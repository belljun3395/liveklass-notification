package com.notification.notification.service.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.notification.notification.domain.FailureClassification;
import com.notification.notification.domain.FailureReasonCode;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationChannel;
import com.notification.notification.domain.NotificationContent;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.service.sender.NotificationSendExecutor.ExecuteResult;
import com.notification.notification.service.sender.NotificationSendExecutor.FailedDispatch;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("NotificationSendExecutor — 디스패처 결과 매핑")
class NotificationSendExecutorTest {

    private static final AtomicLong ID_SEQ = new AtomicLong(1);

    private NotificationSendDispatcher dispatcher;
    private NotificationSendExecutor executor;

    @BeforeEach
    void setUp() {
        dispatcher = mock(NotificationSendDispatcher.class);
        executor = new NotificationSendExecutor(dispatcher);
    }

    private Notification createSendingNotification() {
        NotificationJob job =
                NotificationJob.create(
                        NotificationChannel.EMAIL,
                        "title",
                        "content",
                        "key-" + System.nanoTime(),
                        "TEST",
                        Map.of());
        setId(job, ID_SEQ.getAndIncrement());
        job.markScheduled();
        job.markProcessing();
        Notification n =
                Notification.create(job.getId(), 100L, "test@test.com", Map.of(), "TEST", Map.of());
        setId(n, ID_SEQ.getAndIncrement());
        job.startSendingNotification(n);
        return n;
    }

    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<Long, NotificationContent> contentMapFor(List<Notification> notifications) {
        return notifications.stream()
                .collect(
                        java.util.stream.Collectors.toMap(
                                Notification::getId,
                                n ->
                                        NotificationContent.create(
                                                n.getId(), "rendered title", "rendered body")));
    }

    @Nested
    @DisplayName("정상 응답")
    class NormalResponse {

        @Test
        @DisplayName("전체 성공 → sent에만 포함된다")
        void all_success() {
            Notification n = createSendingNotification();
            List<Notification> notifications = List.of(n);

            when(dispatcher.dispatch(eq(NotificationChannel.EMAIL), any()))
                    .thenReturn(List.of(NotificationService.SendResult.success(n.getId())));

            ExecuteResult result =
                    executor.execute(
                            NotificationChannel.EMAIL, notifications, contentMapFor(notifications));

            assertThat(result.sent()).containsExactly(n);
            assertThat(result.failed()).isEmpty();
        }

        @Test
        @DisplayName("전체 실패 → failed에만 포함되고 classification이 전달된다")
        void all_failure() {
            Notification n = createSendingNotification();
            List<Notification> notifications = List.of(n);

            when(dispatcher.dispatch(eq(NotificationChannel.EMAIL), any()))
                    .thenReturn(
                            List.of(
                                    NotificationService.SendResult.fail(
                                            n.getId(),
                                            "invalid address",
                                            FailureClassification.PERMANENT)));

            ExecuteResult result =
                    executor.execute(
                            NotificationChannel.EMAIL, notifications, contentMapFor(notifications));

            assertThat(result.sent()).isEmpty();
            assertThat(result.failed()).hasSize(1);

            FailedDispatch failed = result.failed().get(0);
            assertThat(failed.notification()).isEqualTo(n);
            assertThat(failed.classification()).isEqualTo(FailureClassification.PERMANENT);
            assertThat(failed.reason()).isEqualTo("invalid address");
        }

        @Test
        @DisplayName("성공과 실패 혼재 시 정확히 분리된다")
        void mixed_success_and_failure() {
            Notification n1 = createSendingNotification();
            Notification n2 = createSendingNotification();
            List<Notification> notifications = List.of(n1, n2);

            when(dispatcher.dispatch(eq(NotificationChannel.EMAIL), any()))
                    .thenReturn(
                            List.of(
                                    NotificationService.SendResult.success(n1.getId()),
                                    NotificationService.SendResult.fail(
                                            n2.getId(),
                                            "timeout",
                                            FailureClassification.TRANSIENT)));

            ExecuteResult result =
                    executor.execute(
                            NotificationChannel.EMAIL, notifications, contentMapFor(notifications));

            assertThat(result.sent()).containsExactly(n1);
            assertThat(result.failed()).hasSize(1);
            assertThat(result.failed().get(0).notification()).isEqualTo(n2);
        }
    }

    @Nested
    @DisplayName("디스패처 결과 누락")
    class MissingResult {

        @Test
        @DisplayName("디스패처가 특정 알림 결과를 반환하지 않으면 NO_DISPATCH_RESULT로 실패 처리된다")
        void missing_result_mapped_to_no_dispatch_result() {
            Notification n1 = createSendingNotification();
            Notification n2 = createSendingNotification();
            List<Notification> notifications = List.of(n1, n2);

            // n1만 결과 반환, n2 결과 누락
            when(dispatcher.dispatch(eq(NotificationChannel.EMAIL), any()))
                    .thenReturn(List.of(NotificationService.SendResult.success(n1.getId())));

            ExecuteResult result =
                    executor.execute(
                            NotificationChannel.EMAIL, notifications, contentMapFor(notifications));

            assertThat(result.sent()).containsExactly(n1);
            assertThat(result.failed()).hasSize(1);

            FailedDispatch failed = result.failed().get(0);
            assertThat(failed.notification()).isEqualTo(n2);
            assertThat(failed.classification()).isEqualTo(FailureClassification.TRANSIENT);
            assertThat(failed.failureReasonCode()).isEqualTo(FailureReasonCode.NO_DISPATCH_RESULT);
        }
    }

    @Nested
    @DisplayName("디스패처 예외")
    class DispatcherException {

        @Test
        @DisplayName("디스패처가 예외를 던지면 전체 알림이 TRANSIENT + DISPATCHER_EXCEPTION으로 실패한다")
        void exception_fails_all_as_transient() {
            Notification n1 = createSendingNotification();
            Notification n2 = createSendingNotification();
            List<Notification> notifications = List.of(n1, n2);

            when(dispatcher.dispatch(eq(NotificationChannel.EMAIL), any()))
                    .thenThrow(new RuntimeException("connection refused"));

            ExecuteResult result =
                    executor.execute(
                            NotificationChannel.EMAIL, notifications, contentMapFor(notifications));

            assertThat(result.sent()).isEmpty();
            assertThat(result.failed()).hasSize(2);

            for (FailedDispatch failed : result.failed()) {
                assertThat(failed.classification()).isEqualTo(FailureClassification.TRANSIENT);
                assertThat(failed.failureReasonCode())
                        .isEqualTo(FailureReasonCode.DISPATCHER_EXCEPTION);
                assertThat(failed.reason()).contains("connection refused");
            }
        }
    }
}
