package com.notification.notification.application.job.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.NotificationChannel;
import com.notification.notification.domain.ScheduleType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record NotificationJobResponse(
        // TSID(~2^60)는 JS Number.MAX_SAFE_INTEGER(2^53-1)를 초과하므로 문자열로 직렬화
        @JsonSerialize(using = ToStringSerializer.class) Long jobId,
        NotificationChannel channel,
        JobStatus status,
        String type,
        Map<String, Object> metadata,
        List<ScheduleRecord> scheduleHistory,
        int totalCount,
        long pendingCount,
        long sendingCount,
        long sentCount,
        long failedCount,
        long retryWaitingCount,
        long deadLetterCount,
        long cancelledCount,
        OffsetDateTime createdAt,
        String lastStatusChangeReason) {

    public record ScheduleRecord(ScheduleType type, OffsetDateTime scheduledAt, boolean executed) {}
}
