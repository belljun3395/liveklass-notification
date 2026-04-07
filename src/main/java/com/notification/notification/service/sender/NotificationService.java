package com.notification.notification.service.sender;

import com.notification.notification.domain.FailureClassification;
import com.notification.notification.domain.NotificationChannel;
import java.util.List;
import java.util.Map;

public interface NotificationService {

    NotificationChannel supportedChannel();

    List<SendResult> send(List<SendPayload> payloads);

    record SendPayload(
            Long notificationId,
            String recipientContact,
            String renderedTitle,
            String renderedContent,
            Map<String, String> metadata) {}

    record SendResult(
            Long notificationId,
            boolean success,
            String failureReason,
            FailureClassification classification) {
        public static SendResult success(Long notificationId) {
            return new SendResult(notificationId, true, null, null);
        }

        public static SendResult fail(
                Long notificationId, String reason, FailureClassification classification) {
            return new SendResult(notificationId, false, reason, classification);
        }
    }
}
