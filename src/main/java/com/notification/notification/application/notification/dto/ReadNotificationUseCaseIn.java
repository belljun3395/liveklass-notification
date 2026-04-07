package com.notification.notification.application.notification.dto;

public record ReadNotificationUseCaseIn(
        Long notificationId, Long userId, String deviceId, String deviceType) {}
