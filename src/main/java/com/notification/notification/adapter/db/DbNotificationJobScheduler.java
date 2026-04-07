package com.notification.notification.adapter.db;

import com.notification.notification.domain.ScheduledNotificationJob;
import com.notification.notification.port.NotificationJobSchedulePort;
import com.notification.notification.repository.job.ScheduledNotificationJobRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DbNotificationJobScheduler implements NotificationJobSchedulePort {

    private final ScheduledNotificationJobRepository scheduledNotificationJobRepository;

    public DbNotificationJobScheduler(
            ScheduledNotificationJobRepository scheduledNotificationJobRepository) {
        this.scheduledNotificationJobRepository = scheduledNotificationJobRepository;
    }

    @Override
    public void schedule(Long jobId, OffsetDateTime scheduledAt) {
        scheduledNotificationJobRepository.save(
                ScheduledNotificationJob.create(jobId, scheduledAt));
    }

    @Override
    public void retrySchedule(Long jobId, OffsetDateTime retryAt) {
        scheduledNotificationJobRepository.save(
                ScheduledNotificationJob.createRetry(jobId, retryAt));
    }

    @Override
    public void cancelSchedule(Long jobId) {
        List<ScheduledNotificationJob> pending =
                scheduledNotificationJobRepository.findByJobIdAndExecutedFalse(jobId);
        pending.forEach(ScheduledNotificationJob::markExecuted);
        scheduledNotificationJobRepository.saveAll(pending);
    }
}
