package com.notification.notification.event;

import java.time.OffsetDateTime;

/**
 * 외부 스케줄러에 대한 등록/해제 요청 이벤트입니다.
 *
 * <p>상태 전이 트랜잭션 안에서 발행되며, 커밋 후 별도 트랜잭션에서 소비됩니다. 이렇게 분리하는 이유는:
 *
 * <ol>
 *   <li>외부 호출 응답 대기 동안 상태 전이 트랜잭션의 행 잠금이 유지되는 것을 방지
 *   <li>외부 호출 성공 후 트랜잭션 롤백 시 발생하는 DB-외부 서비스 간 불일치 방지
 * </ol>
 */
public record NotificationJobScheduleEvent(
        Long jobId, ScheduleAction action, OffsetDateTime scheduledAt)
        implements NotificationEvent {

    public enum ScheduleAction {
        /** 최초 발송 스케줄 등록 */
        REGISTER,
        /** 재시도 스케줄 등록 */
        RETRY,
        /** 스케줄 해제 */
        CANCEL
    }

    public static NotificationJobScheduleEvent register(Long jobId, OffsetDateTime scheduledAt) {
        return new NotificationJobScheduleEvent(jobId, ScheduleAction.REGISTER, scheduledAt);
    }

    public static NotificationJobScheduleEvent retry(Long jobId, OffsetDateTime retryAt) {
        return new NotificationJobScheduleEvent(jobId, ScheduleAction.RETRY, retryAt);
    }

    public static NotificationJobScheduleEvent cancel(Long jobId) {
        return new NotificationJobScheduleEvent(jobId, ScheduleAction.CANCEL, null);
    }
}
