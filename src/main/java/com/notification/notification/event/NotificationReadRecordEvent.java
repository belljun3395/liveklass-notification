package com.notification.notification.event;

import java.time.OffsetDateTime;

public record NotificationReadRecordEvent(
        Long notificationId, Long userId, String deviceId, String deviceType, OffsetDateTime readAt)
        implements NotificationEvent {}
