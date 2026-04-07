package com.notification.notification.event.listener.spring;

import com.notification.notification.event.NotificationReadRecordEvent;
import com.notification.notification.event.processor.NotificationReadProcessor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring 이벤트 소비 어댑터. 처리 로직은 {@link NotificationReadProcessor}에 위임합니다.
 *
 * <p>읽음 이벤트는 Spring Modulith의 {@code @ApplicationModuleListener} 대신 {@code @EventListener + @Async
 * + @Transactional}을 사용합니다. {@link
 * com.notification.notification.application.notification.ReadNotificationUseCase}가 트랜잭션 없이 이벤트만
 * 발행하므로, 리스너 쪽에서 트랜잭션을 관리합니다.
 */
@Component
public class NotificationReadSpringAdapter {

    private final NotificationReadProcessor processor;

    public NotificationReadSpringAdapter(NotificationReadProcessor processor) {
        this.processor = processor;
    }

    @EventListener
    @Async
    @Transactional
    public void handle(NotificationReadRecordEvent event) {
        processor.process(event);
    }
}
