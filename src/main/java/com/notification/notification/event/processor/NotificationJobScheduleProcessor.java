package com.notification.notification.event.processor;

import com.notification.notification.event.NotificationJobScheduleEvent;
import com.notification.notification.port.NotificationJobSchedulePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link NotificationJobScheduleEvent} 처리 로직.
 *
 * <p>상태 전이 트랜잭션이 커밋된 후 별도 트랜잭션에서 실행됩니다. 외부 스케줄러 호출이 실패해도 상태 전이는 이미 커밋되어 있으므로 DB 일관성에는 영향이 없습니다.
 *
 * <p>실패 시 영향:
 *
 * <ul>
 *   <li>REGISTER 실패: Job은 SCHEDULED이지만 스케줄 미등록 → 발송 트리거 안 됨, 수동 복구 필요
 *   <li>RETRY 실패: Job은 FAILED이지만 재시도 미등록 → RecoverNotificationJobUseCase로 수동 복구 가능
 *   <li>CANCEL 실패: 고아 스케줄 잔존 → Relay가 도달해도 Job이 CANCELLED이므로 markProcessing() 실패로 스킵
 * </ul>
 */
@Slf4j
@Component
public class NotificationJobScheduleProcessor {

    private final NotificationJobSchedulePort schedulePort;

    public NotificationJobScheduleProcessor(NotificationJobSchedulePort schedulePort) {
        this.schedulePort = schedulePort;
    }

    public void process(NotificationJobScheduleEvent event) {
        switch (event.action()) {
            case REGISTER -> {
                schedulePort.schedule(event.jobId(), event.scheduledAt());
                log.info(
                        "[Handler:Schedule] Job {} — schedule registered at {}",
                        event.jobId(),
                        event.scheduledAt());
            }
            case RETRY -> {
                schedulePort.retrySchedule(event.jobId(), event.scheduledAt());
                log.info(
                        "[Handler:Schedule] Job {} — retry scheduled at {}",
                        event.jobId(),
                        event.scheduledAt());
            }
            case CANCEL -> {
                schedulePort.cancelSchedule(event.jobId());
                log.info("[Handler:Schedule] Job {} — schedule cancelled", event.jobId());
            }
        }
    }
}
