package com.notification.notification.port;

import java.time.OffsetDateTime;

public interface NotificationJobSchedulePort {

    void schedule(Long jobId, OffsetDateTime scheduledAt);

    void retrySchedule(Long jobId, OffsetDateTime retryAt);

    void cancelSchedule(Long jobId);
}
