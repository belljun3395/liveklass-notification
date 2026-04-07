package com.notification.notification.event.listener.spring;

import com.notification.notification.event.NotificationJobScheduleEvent;
import com.notification.notification.event.processor.NotificationJobScheduleProcessor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Spring Modulith 소비 어댑터. 처리 로직은 {@link NotificationJobScheduleProcessor}에 위임합니다. */
@Component
public class NotificationJobScheduleSpringAdapter {

    private final NotificationJobScheduleProcessor processor;

    public NotificationJobScheduleSpringAdapter(NotificationJobScheduleProcessor processor) {
        this.processor = processor;
    }

    @ApplicationModuleListener
    public void handle(NotificationJobScheduleEvent event) {
        processor.process(event);
    }
}
