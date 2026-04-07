package com.notification.notification.domain;

public enum FailureReasonCode {
    /** 디스패처에서 예외가 발생해 모든 알림이 실패로 처리됨 */
    DISPATCHER_EXCEPTION,
    /** 디스패처가 특정 알림에 대한 결과를 반환하지 않음 */
    NO_DISPATCH_RESULT,
    /** FailureClassification.PERMANENT — 재시도 없이 즉시 DEAD_LETTER */
    PERMANENT_FAILURE,
    /** 최대 재시도 횟수 초과로 DEAD_LETTER */
    RETRY_EXHAUSTED,
    /** SENDING 상태에서 타임아웃으로 DEAD_LETTER (stuck recovery) */
    STUCK_TIMEOUT
}
