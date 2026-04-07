package com.notification.notification.event.listener.spring;

import com.notification.notification.event.NotificationJobStatusChangedEvent;
import com.notification.notification.event.processor.NotificationJobStatusChangedProcessor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Spring Modulith 소비 어댑터. 처리 로직은 {@link NotificationJobStatusChangedProcessor}에 위임합니다. */
@Component
public class NotificationJobStatusChangedSpringAdapter {

    private final NotificationJobStatusChangedProcessor processor;

    public NotificationJobStatusChangedSpringAdapter(
            NotificationJobStatusChangedProcessor processor) {
        this.processor = processor;
    }

    @ApplicationModuleListener
    public void handle(NotificationJobStatusChangedEvent event) {
        processor.process(event);
    }
}
