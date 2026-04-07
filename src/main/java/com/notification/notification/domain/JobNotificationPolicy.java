package com.notification.notification.domain;

import java.util.Set;

/**
 * Job 상태가 변경될 때 촉발되는 Notification 상태 전이를 정의합니다.
 *
 * <p>각 정책은 하나의 Job 상태 전이(fromStatuses → toStatus)를 나타내며, 해당 전이로 인해 시작되는 Notification 전이 체인 전체를
 * {@link NotificationTransition}의 집합으로 선언합니다.
 *
 * <p>모든 {@link JobStatus} 간 허용된 전이, 그리고 모든 {@link NotificationStatus} 간 허용된 전이는 반드시 하나 이상의 정책에 의해
 * 커버되어야 합니다.
 *
 * <p>런타임에서는 Job 상태 전이(from → to)로 정책을 동적으로 조회하지 않고, 호출부가 필요한 enum 상수를 명시적으로 선택해 사용합니다.
 */
public enum JobNotificationPolicy {

    /** Job → SCHEDULED. Notification 영향 없음. */
    TO_SCHEDULED(Set.of(JobStatus.CREATED), JobStatus.SCHEDULED, Set.of()),

    /**
     * Job → PROCESSING. 발송이 시작되며 Notification 전이 체인이 촉발됩니다.
     *
     * <p>PENDING/RETRY_WAITING → SENDING → SENT/FAILED → RETRY_WAITING/DEAD_LETTER
     */
    TO_PROCESSING(
            Set.of(JobStatus.SCHEDULED, JobStatus.RETRYING),
            JobStatus.PROCESSING,
            Set.of(
                    NotificationTransition.of(
                            NotificationStatus.PENDING, NotificationStatus.SENDING),
                    NotificationTransition.of(
                            NotificationStatus.RETRY_WAITING, NotificationStatus.SENDING),
                    NotificationTransition.of(NotificationStatus.SENDING, NotificationStatus.SENT),
                    NotificationTransition.of(
                            NotificationStatus.SENDING, NotificationStatus.FAILED),
                    NotificationTransition.of(
                            NotificationStatus.FAILED, NotificationStatus.RETRY_WAITING),
                    NotificationTransition.of(
                            NotificationStatus.FAILED, NotificationStatus.DEAD_LETTER))),

    /**
     * Job → CANCELLED. 취소 가능한 Notification을 CANCELLED로 전이합니다.
     *
     * <p>발송이 시작되지 않은 상태에서만 취소 가능. SENDING 상태 Notification은 진행 중이므로 취소 대상이 아닙니다.
     */
    TO_CANCELLED(
            Set.of(JobStatus.CREATED, JobStatus.SCHEDULED, JobStatus.RETRYING),
            JobStatus.CANCELLED,
            Set.of(
                    NotificationTransition.of(
                            NotificationStatus.PENDING, NotificationStatus.CANCELLED),
                    NotificationTransition.of(
                            NotificationStatus.RETRY_WAITING, NotificationStatus.CANCELLED),
                    NotificationTransition.of(
                            NotificationStatus.FAILED, NotificationStatus.CANCELLED),
                    NotificationTransition.of(
                            NotificationStatus.DEAD_LETTER, NotificationStatus.CANCELLED))),

    /**
     * Job → RETRYING. 실패/취소된 Notification을 재시도 대기 상태로 리셋합니다.
     *
     * <p>DEAD_LETTER/CANCELLED → RETRY_WAITING. 실제 발송은 RETRYING → PROCESSING 전이 후 TO_PROCESSING 정책에
     * 의해 수행됩니다.
     */
    TO_RETRYING(
            Set.of(JobStatus.FAILED, JobStatus.CANCELLED),
            JobStatus.RETRYING,
            Set.of(
                    NotificationTransition.of(
                            NotificationStatus.DEAD_LETTER, NotificationStatus.RETRY_WAITING),
                    NotificationTransition.of(
                            NotificationStatus.CANCELLED, NotificationStatus.RETRY_WAITING))),

    /** Job → COMPLETED. 모든 Notification이 이미 terminal 상태. Notification 영향 없음. */
    TO_COMPLETED(Set.of(JobStatus.PROCESSING), JobStatus.COMPLETED, Set.of()),

    /** Job → FAILED. 모든 Notification이 이미 terminal 상태이거나 RETRY_WAITING 존재. Notification 영향 없음. */
    TO_FAILED(Set.of(JobStatus.PROCESSING), JobStatus.FAILED, Set.of()),

    /**
     * 스턱 PROCESSING 복구. 잔류 SENDING Notification을 DEAD_LETTER로 전이하고 Job을 FAILED로 전이합니다.
     *
     * <p>{@code StuckProcessingRecoveryScheduler} 전용. DEAD_LETTER로 전이함으로써 수동 복구({@code
     * RecoverNotificationJobUseCase}) 시 복구 핸들러가 해당 알림을 RETRY_WAITING으로 초기화할 수 있습니다.
     */
    STUCK_RECOVERY(
            Set.of(JobStatus.PROCESSING),
            JobStatus.FAILED,
            Set.of(
                    NotificationTransition.of(
                            NotificationStatus.SENDING, NotificationStatus.DEAD_LETTER))),

    /**
     * 인프라 크래시 복구. 잔류 SENDING Notification을 RETRY_WAITING으로 롤백합니다.
     *
     * <p>서버 재시작 시 {@code NotificationJobRestartReconciler}가 사용합니다. Job 상태는 PROCESSING으로 유지되며, 다음 실행
     * 사이클에서 정상 재처리됩니다.
     */
    STUCK_SENDING_ROLLBACK(
            Set.of(JobStatus.PROCESSING),
            JobStatus.PROCESSING,
            Set.of(
                    NotificationTransition.of(
                            NotificationStatus.SENDING, NotificationStatus.RETRY_WAITING)));

    private final Set<JobStatus> fromStatuses;
    private final JobStatus toStatus;
    private final Set<NotificationTransition> notificationTransitions;

    JobNotificationPolicy(
            Set<JobStatus> fromStatuses,
            JobStatus toStatus,
            Set<NotificationTransition> notificationTransitions) {
        this.fromStatuses = fromStatuses;
        this.toStatus = toStatus;
        this.notificationTransitions = notificationTransitions;
    }

    public Set<JobStatus> getFromStatuses() {
        return fromStatuses;
    }

    public JobStatus getToStatus() {
        return toStatus;
    }

    public Set<NotificationTransition> getNotificationTransitions() {
        return notificationTransitions;
    }

    public boolean hasNotificationEffect() {
        return !notificationTransitions.isEmpty();
    }

    public record NotificationTransition(NotificationStatus from, NotificationStatus to) {
        public static NotificationTransition of(NotificationStatus from, NotificationStatus to) {
            return new NotificationTransition(from, to);
        }
    }
}
