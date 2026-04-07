package com.notification.notification.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record CreateNotificationJobRequest(
        @NotBlank String idempotencyKey,
        @NotNull NotificationChannelDto channel,
        @NotBlank String templateCode,
        String locale,
        String type,
        Map<String, Object> metadata,
        @NotNull OffsetDateTime scheduledAt,
        @NotEmpty @Valid List<Recipient> recipients) {

    public record Recipient(
            @NotNull Long recipientId, @NotBlank String contact, Map<String, String> variables) {}
}
