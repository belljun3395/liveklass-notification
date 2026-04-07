package com.notification.notification.event;

public record NotificationJobExecutionEvent(
        Long jobId, String idempotencyKey, String statusChangeReason)
        implements NotificationEvent {}
