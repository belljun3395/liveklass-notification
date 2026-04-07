package com.notification.notification.event.listener.spring;

import com.notification.notification.event.NotificationJobCreatedAfterCommitEvent;
import com.notification.notification.event.processor.NotificationJobCreatedProcessor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Spring Modulith 소비 어댑터. 처리 로직은 {@link NotificationJobCreatedProcessor}에 위임합니다. */
@Component
public class NotificationJobCreatedSpringAdapter {

    private final NotificationJobCreatedProcessor processor;

    public NotificationJobCreatedSpringAdapter(NotificationJobCreatedProcessor processor) {
        this.processor = processor;
    }

    @ApplicationModuleListener
    public void handle(NotificationJobCreatedAfterCommitEvent event) {
        processor.process(event);
    }
}
