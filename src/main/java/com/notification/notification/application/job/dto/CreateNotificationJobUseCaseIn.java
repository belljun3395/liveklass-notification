package com.notification.notification.application.job.dto;

import com.notification.notification.domain.NotificationChannel;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record CreateNotificationJobUseCaseIn(
        String idempotencyKey,
        NotificationChannel channel,
        String templateCode,
        String locale,
        String type,
        Map<String, Object> metadata,
        OffsetDateTime scheduledAt,
        List<Recipient> recipients) {

    public record Recipient(Long recipientId, String contact, Map<String, String> variables) {}
}
