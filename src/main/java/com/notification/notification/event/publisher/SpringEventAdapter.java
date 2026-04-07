package com.notification.notification.event.publisher;

import com.notification.notification.event.NotificationEvent;
import com.notification.notification.event.NotificationJobCreatedAfterCommitEvent;
import com.notification.notification.event.NotificationJobExecutionEvent;
import com.notification.notification.event.NotificationJobScheduleEvent;
import com.notification.notification.event.NotificationJobStatusChangedEvent;
import com.notification.notification.event.NotificationReadRecordEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class SpringEventAdapter implements NotificationEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public SpringEventAdapter(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void publish(NotificationEvent event) {
        // this is for debugging and monitoring purpose
        switch (event) {
            case NotificationJobCreatedAfterCommitEvent e -> eventPublisher.publishEvent(e);
            case NotificationJobExecutionEvent e -> eventPublisher.publishEvent(e);
            case NotificationJobScheduleEvent e -> eventPublisher.publishEvent(e);
            case NotificationJobStatusChangedEvent e -> eventPublisher.publishEvent(e);
            case NotificationReadRecordEvent e -> eventPublisher.publishEvent(e);
            default -> eventPublisher.publishEvent(event);
        }
    }
}
