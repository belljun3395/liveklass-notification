package com.notification.notification.event.processor;

import com.notification.notification.event.NotificationJobStatusChangedEvent;
import com.notification.notification.service.job.JobStatusHistoryRecorder;
import org.springframework.stereotype.Component;

/**
 * {@link NotificationJobStatusChangedEvent} 처리 로직.
 *
 * <p>이 클래스는 소비 방식(Spring Modulith, 브로커 Consumer 등)에 무관하게 재사용 가능합니다.
 */
@Component
public class NotificationJobStatusChangedProcessor {

    private final JobStatusHistoryRecorder historyRecorder;

    public NotificationJobStatusChangedProcessor(JobStatusHistoryRecorder historyRecorder) {
        this.historyRecorder = historyRecorder;
    }

    public void process(NotificationJobStatusChangedEvent event) {
        historyRecorder.execute(
                event.jobId(),
                event.preStatus(),
                event.status(),
                event.reason(),
                event.triggeredBy());
    }
}
