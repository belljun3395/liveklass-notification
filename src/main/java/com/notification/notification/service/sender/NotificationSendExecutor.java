package com.notification.notification.service.sender;

import com.notification.notification.domain.FailureClassification;
import com.notification.notification.domain.FailureReasonCode;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationChannel;
import com.notification.notification.domain.NotificationContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 알림 목록을 채널로 발송하고 결과를 반환합니다. 상태 전이와 저장은 수행하지 않습니다. */
@Slf4j
@Component
public class NotificationSendExecutor {

    private final NotificationSendDispatcher notificationSendDispatcher;

    public NotificationSendExecutor(NotificationSendDispatcher notificationSendDispatcher) {
        this.notificationSendDispatcher = notificationSendDispatcher;
    }

    public record ExecuteResult(List<Notification> sent, List<FailedDispatch> failed) {}

    public record FailedDispatch(
            Notification notification,
            FailureClassification classification,
            String reason,
            FailureReasonCode failureReasonCode) {}

    /**
     * 전달받은 알림 목록을 채널로 발송하고 결과를 반환합니다.
     *
     * <p>알림은 이미 {@code SENDING} 상태로 전이된 상태로 전달됩니다.
     *
     * @param channel 발송 채널
     * @param notifications 발송할 알림 목록
     * @param contentMap notificationId → 렌더링된 콘텐츠 매핑
     * @return 성공({@code sent}) 및 실패({@code failed}) 목록
     */
    public ExecuteResult execute(
            NotificationChannel channel,
            List<Notification> notifications,
            Map<Long, NotificationContent> contentMap) {

        List<NotificationService.SendPayload> payloads = buildPayloads(notifications, contentMap);

        List<NotificationService.SendResult> results;
        try {
            results = notificationSendDispatcher.dispatch(channel, payloads);
        } catch (Exception e) {
            String reason = "Dispatcher exception: " + e.getMessage();
            log.error("[SendExecutor] Dispatcher threw exception for channel {}", channel, e);
            List<FailedDispatch> allFailed =
                    notifications.stream()
                            .map(
                                    n ->
                                            new FailedDispatch(
                                                    n,
                                                    FailureClassification.TRANSIENT,
                                                    reason,
                                                    FailureReasonCode.DISPATCHER_EXCEPTION))
                            .toList();
            return new ExecuteResult(List.of(), allFailed);
        }

        Map<Long, NotificationService.SendResult> resultMap =
                results.stream()
                        .collect(
                                Collectors.toMap(
                                        NotificationService.SendResult::notificationId, r -> r));

        List<Notification> sent = new ArrayList<>();
        List<FailedDispatch> failed = new ArrayList<>();

        for (Notification n : notifications) {
            NotificationService.SendResult result = resultMap.get(n.getId());
            if (result != null && result.success()) {
                sent.add(n);
            } else {
                FailureClassification classification =
                        result != null ? result.classification() : FailureClassification.TRANSIENT;
                String reason =
                        result != null
                                ? result.failureReason()
                                : "No result returned by dispatcher for this notification";
                // 발송 실패 시 항상 failure_code를 기록
                // dispatcher가 결과를 반환하지 않은 경우만 NO_DISPATCH_RESULT 설정
                FailureReasonCode code = FailureReasonCode.NO_DISPATCH_RESULT;
                failed.add(new FailedDispatch(n, classification, reason, code));
            }
        }

        log.info(
                "[SendExecutor] channel={} — sent: {}, failed: {}",
                channel,
                sent.size(),
                failed.size());
        return new ExecuteResult(sent, failed);
    }

    private List<NotificationService.SendPayload> buildPayloads(
            List<Notification> notifications, Map<Long, NotificationContent> contentMap) {
        return notifications.stream()
                .map(
                        n -> {
                            NotificationContent content = contentMap.get(n.getId());
                            return new NotificationService.SendPayload(
                                    n.getId(),
                                    n.getRecipientContact(),
                                    content.getRenderedTitle(),
                                    content.getRenderedBody(),
                                    Map.of());
                        })
                .toList();
    }
}
