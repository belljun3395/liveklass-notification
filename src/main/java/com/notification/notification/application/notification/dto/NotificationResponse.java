package com.notification.notification.application.notification.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationChannel;
import com.notification.notification.domain.NotificationStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

public record NotificationResponse(
        // TSID(~2^60)는 JS Number.MAX_SAFE_INTEGER(2^53-1)를 초과하므로 문자열로 직렬화
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        @JsonSerialize(using = ToStringSerializer.class) Long jobId,
        // recipientId는 시스템 생성 TSID가 아닌 외부 입력값이나,
        // 연동 시스템이 64비트 ID를 사용할 수 있으므로 동일하게 문자열로 직렬화
        @JsonSerialize(using = ToStringSerializer.class) Long recipientId,
        NotificationChannel channel,
        NotificationStatus status,
        String type,
        Map<String, Object> metadata,
        int attemptCount,
        String lastFailureReason,
        OffsetDateTime firstReadAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String renderedTitle,
        String renderedBody) {

    public static NotificationResponse from(
            Notification n,
            NotificationChannel channel,
            String renderedTitle,
            String renderedBody,
            ZoneOffset timezone) {
        return new NotificationResponse(
                n.getId(),
                n.getJobId(),
                n.getRecipientId(),
                channel,
                n.getStatus(),
                n.getType(),
                n.getMetadata(),
                n.getSendTryCount(),
                n.getLastFailureReason(),
                toOffset(n.getFirstReadAt(), timezone),
                toOffset(n.getCreatedAt(), timezone),
                toOffset(n.getUpdatedAt(), timezone),
                renderedTitle,
                renderedBody);
    }

    private static OffsetDateTime toOffset(OffsetDateTime odt, ZoneOffset timezone) {
        return odt == null ? null : odt.withOffsetSameInstant(timezone);
    }
}
