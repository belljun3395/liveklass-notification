package com.notification.notification.application.job;

import com.notification.notification.application.job.dto.NotificationJobResponse;
import com.notification.notification.application.job.dto.NotificationJobResponse.ScheduleRecord;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.domain.NotificationJobStatusChangeHistory;
import com.notification.notification.domain.NotificationStatus;
import com.notification.notification.repository.job.NotificationJobRepository;
import com.notification.notification.repository.job.NotificationJobStatusChangeHistoryRepository;
import com.notification.notification.repository.job.ScheduledNotificationJobRepository;
import com.notification.notification.repository.notification.NotificationRepository;
import com.notification.support.web.exception.ResourceNotFoundException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 잡의 현재 상태와 수신자별 발송 집계를 조회합니다.
 *
 * <p><b>처리 흐름:</b>
 *
 * <ol>
 *   <li>알림 잡 조회 — 존재하지 않거나 삭제된 잡이면 {@link ResourceNotFoundException}
 *   <li>해당 잡의 전체 알림을 단일 쿼리로 조회하고, 메모리에서 상태별로 집계
 *   <li>모든 스케줄 내역(INITIAL 최초 예약 + RETRY 재시도)을 {@code scheduledAt} 오름차순으로 조회
 *   <li>마지막 상태 변경 사유 조회
 * </ol>
 *
 * @see CreateScheduledNotificationJobUseCase
 */
@Component
public class GetNotificationJobUseCase {

    private final NotificationJobRepository jobRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationJobStatusChangeHistoryRepository historyRepository;
    private final ScheduledNotificationJobRepository scheduledJobRepository;
    private final ZoneOffset responseTimezone;

    public GetNotificationJobUseCase(
            NotificationJobRepository jobRepository,
            NotificationRepository notificationRepository,
            NotificationJobStatusChangeHistoryRepository historyRepository,
            ScheduledNotificationJobRepository scheduledJobRepository,
            @Value("${notification.response-timezone:+09:00}") ZoneOffset responseTimezone) {
        this.jobRepository = jobRepository;
        this.notificationRepository = notificationRepository;
        this.historyRepository = historyRepository;
        this.scheduledJobRepository = scheduledJobRepository;
        this.responseTimezone = responseTimezone;
    }

    @Transactional(readOnly = true)
    public NotificationJobResponse execute(Long jobId) {
        NotificationJob job =
                jobRepository
                        .findByIdAndDeletedFalse(jobId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Job not found: " + jobId));

        List<Notification> notifications =
                notificationRepository.findAllByJobIdAndDeletedFalse(jobId);
        Map<NotificationStatus, Long> countByStatus =
                notifications.stream()
                        .collect(
                                Collectors.groupingBy(
                                        Notification::getStatus, Collectors.counting()));

        List<ScheduleRecord> scheduleHistory =
                scheduledJobRepository.findAllByJobIdOrderByScheduledAtAsc(jobId).stream()
                        .map(
                                s ->
                                        new ScheduleRecord(
                                                s.getType(),
                                                s.getScheduledAt()
                                                        .withOffsetSameInstant(responseTimezone),
                                                s.isExecuted()))
                        .toList();

        String lastStatusChangeReason =
                historyRepository
                        .findTopByJobIdOrderByCreatedAtDesc(jobId)
                        .map(NotificationJobStatusChangeHistory::getStatusChangeReason)
                        .orElse(null);

        return new NotificationJobResponse(
                job.getId(),
                job.getChannel(),
                job.getStatus(),
                job.getType(),
                job.getMetadata(),
                scheduleHistory,
                notifications.size(),
                count(countByStatus, NotificationStatus.PENDING),
                count(countByStatus, NotificationStatus.SENDING),
                count(countByStatus, NotificationStatus.SENT),
                count(countByStatus, NotificationStatus.FAILED),
                count(countByStatus, NotificationStatus.RETRY_WAITING),
                count(countByStatus, NotificationStatus.DEAD_LETTER),
                count(countByStatus, NotificationStatus.CANCELLED),
                job.getCreatedAt().withOffsetSameInstant(responseTimezone),
                lastStatusChangeReason);
    }

    private long count(Map<NotificationStatus, Long> countByStatus, NotificationStatus status) {
        return countByStatus.getOrDefault(status, 0L);
    }
}
