package com.notification.notification.application.notification;

import com.notification.notification.application.notification.dto.ReadNotificationUseCaseIn;
import com.notification.notification.event.NotificationReadRecordEvent;
import com.notification.notification.event.publisher.NotificationEventPublisher;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

@Component
public class ReadNotificationUseCase {

    private final NotificationEventPublisher eventPublisher;

    public ReadNotificationUseCase(NotificationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void execute(ReadNotificationUseCaseIn useCaseIn) {
        eventPublisher.publish(
                new NotificationReadRecordEvent(
                        useCaseIn.notificationId(),
                        useCaseIn.userId(),
                        useCaseIn.deviceId(),
                        useCaseIn.deviceType(),
                        OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
