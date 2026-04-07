package com.notification.notification.domain;

import com.notification.infra.util.id.TsidId;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notification_read_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationReadEvent {

    @TsidId @Id private Long id;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "device_type", length = 50)
    private String deviceType;

    @Column(name = "read_at", nullable = false)
    private OffsetDateTime readAt;

    public static NotificationReadEvent create(
            Long notificationId,
            Long userId,
            String deviceId,
            String deviceType,
            OffsetDateTime readAt) {
        NotificationReadEvent event = new NotificationReadEvent();
        event.notificationId = notificationId;
        event.userId = userId;
        event.deviceId = deviceId;
        event.deviceType = deviceType;
        event.readAt = readAt;
        return event;
    }
}
