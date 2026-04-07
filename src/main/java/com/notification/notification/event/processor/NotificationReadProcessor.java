package com.notification.notification.event.processor;

import com.notification.notification.domain.NotificationReadEvent;
import com.notification.notification.event.NotificationReadRecordEvent;
import com.notification.notification.repository.notification.NotificationReadEventRepository;
import com.notification.notification.repository.notification.NotificationRepository;
import com.notification.support.web.exception.ResourceNotFoundException;
import org.springframework.stereotype.Component;

/**
 * {@link NotificationReadRecordEvent} 처리 로직.
 *
 * <p>이 클래스는 소비 방식(Spring Modulith, 브로커 Consumer 등)에 무관하게 재사용 가능합니다.
 */
@Component
public class NotificationReadProcessor {

    private final NotificationRepository notificationRepository;
    private final NotificationReadEventRepository notificationReadEventRepository;

    public NotificationReadProcessor(
            NotificationRepository notificationRepository,
            NotificationReadEventRepository notificationReadEventRepository) {
        this.notificationRepository = notificationRepository;
        this.notificationReadEventRepository = notificationReadEventRepository;
    }

    public void process(NotificationReadRecordEvent event) {
        int updated =
                notificationRepository.markFirstReadAt(event.notificationId(), event.readAt());
        if (updated == 0) {
            throw new ResourceNotFoundException(
                    "Notification not found: " + event.notificationId());
        }
        notificationReadEventRepository.save(
                NotificationReadEvent.create(
                        event.notificationId(),
                        event.userId(),
                        event.deviceId(),
                        event.deviceType(),
                        event.readAt()));
    }
}
