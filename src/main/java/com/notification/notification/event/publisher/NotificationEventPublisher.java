package com.notification.notification.event.publisher;

import com.notification.notification.event.NotificationEvent;

public interface NotificationEventPublisher {

    void publish(NotificationEvent event);
}
