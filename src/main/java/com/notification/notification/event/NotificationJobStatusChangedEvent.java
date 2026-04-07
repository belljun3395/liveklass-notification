package com.notification.notification.event;

import com.notification.notification.domain.JobStatus;

public record NotificationJobStatusChangedEvent(
        Long jobId, JobStatus preStatus, JobStatus status, String reason, String triggeredBy)
        implements NotificationEvent {}
