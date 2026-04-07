package com.notification.notification.domain;

/**
 * ScheduledNotificationJob의 실행 유형을 구분합니다.
 *
 * <p>INITIAL: 관리자가 예약한 최초 발송 스케줄. ScheduledNotificationJobRelay가 처리하며, 템플릿 렌더링 후 PENDING 알림을 발송합니다.
 *
 * <p>RETRY: NotificationJobRecoverRelay가 FAILED 알림을 RETRY_WAITING으로 전이한 뒤 생성하는 재시도 스케줄.
 * RetryNotificationJobRelay가 처리하며, 이미 렌더링된 콘텐츠를 재사용하여 RETRY_WAITING 알림을 발송합니다.
 */
public enum ScheduleType {
    INITIAL,
    RETRY
}
