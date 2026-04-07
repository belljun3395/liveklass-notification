package com.notification.notification.event;

import java.time.OffsetDateTime;

/**
 * Job 생성 TX 커밋 + 락 해제 이후에 발행되는 이벤트입니다.
 *
 * <p>발행 시점이 TX 커밋 이후이므로, 크래시로 인한 이벤트 유실 가능성이 있습니다. {@code CreatedNotificationJobRecoveryScheduler}가
 * CREATED 상태 잔류 잡을 폴링하여 보완합니다.
 */
public record NotificationJobCreatedAfterCommitEvent(
        Long jobId, String idempotencyKey, OffsetDateTime scheduledAt)
        implements NotificationEvent {}
