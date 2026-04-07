package com.notification.notification.event.listener.spring;

import com.notification.notification.event.NotificationJobExecutionEvent;
import com.notification.notification.event.processor.NotificationJobExecutionProcessor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Spring Modulith 소비 어댑터. 처리 로직은 {@link NotificationJobExecutionProcessor}에 위임합니다. */
@Component
public class NotificationJobExecutionSpringAdapter {

    private final NotificationJobExecutionProcessor processor;

    public NotificationJobExecutionSpringAdapter(NotificationJobExecutionProcessor processor) {
        this.processor = processor;
    }

    @ApplicationModuleListener
    public void handle(NotificationJobExecutionEvent event) {
        processor.process(event);
    }
}
