package com.notification.notification.application.job;

import com.notification.infra.port.DistributedLock;
import com.notification.infra.port.IdempotencyStore;
import com.notification.notification.application.job.dto.CreateNotificationJobUseCaseIn;
import com.notification.notification.application.job.dto.CreateNotificationJobUseCaseOut;
import com.notification.notification.application.job.dto.NotificationJobResponse;
import com.notification.notification.application.job.dto.NotificationJobResponse.ScheduleRecord;
import com.notification.notification.config.properties.NotificationProperties;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.domain.ScheduleType;
import com.notification.notification.event.NotificationJobCreatedAfterCommitEvent;
import com.notification.notification.event.publisher.NotificationEventPublisher;
import com.notification.notification.port.TemplateResolver;
import com.notification.notification.repository.job.NotificationJobRepository;
import com.notification.notification.repository.job.ScheduledNotificationJobRepository;
import com.notification.notification.repository.notification.NotificationRepository;
import com.notification.support.web.exception.DuplicateResourceException;
import io.micrometer.observation.annotation.Observed;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예약 알림 잡을 생성하고 스케줄링 파이프라인의 시작점 역할을 합니다.
 *
 * <p>이 유즈케이스는 {@link IssueCreationJobKeyUseCase}에서 발급받은 멱등성 키와 함께 호출되어야 합니다. 발급되지 않은 키로 호출하면 예외를
 * 발생시킵니다.
 *
 * <p><b>처리 흐름:</b>
 *
 * <ol>
 *   <li>발급된 키인지 검증 — 미발급 키이면 {@link IllegalArgumentException}
 *   <li>분산 락({@code distributedLock.tryLock})으로 동시 중복 생성 차단
 *   <li>동일 키의 기존 Job이 있으면 생성 없이 기존 Job 반환 (멱등 응답)
 *   <li>템플릿 조회 → {@code NotificationJob}(CREATED) + {@code Notification}(PENDING) × N 생성
 *   <li>분산 락 해제 — TX 커밋 전에 해제하여 AfterCommit 핸들러의 락 경합 방지
 *   <li>{@link com.notification.notification.event.NotificationJobCreatedAfterCommitEvent} 발행
 *   <li>TX 커밋 → 핸들러 디스패치
 * </ol>
 *
 * @see IssueCreationJobKeyUseCase
 */
@Slf4j
@Component
public class CreateScheduledNotificationJobUseCase {

    private final Duration idempotencyTtl;
    private final IdempotencyStore idempotencyStore;
    private final DistributedLock distributedLock;
    private final NotificationJobRepository jobRepository;
    private final NotificationRepository notificationRepository;
    private final ScheduledNotificationJobRepository scheduledJobRepository;
    private final TemplateResolver templateResolver;
    private final NotificationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ZoneId responseTimezone;
    private final ZoneId applicationTimezone;

    public CreateScheduledNotificationJobUseCase(
            IdempotencyStore idempotencyStore,
            DistributedLock distributedLock,
            NotificationJobRepository jobRepository,
            NotificationRepository notificationRepository,
            ScheduledNotificationJobRepository scheduledJobRepository,
            TemplateResolver templateResolver,
            NotificationEventPublisher eventPublisher,
            PlatformTransactionManager txManager,
            NotificationProperties properties,
            @Value("${notification.response-timezone:Asia/Seoul}") String responseTimezoneStr) {
        this.idempotencyStore = idempotencyStore;
        this.distributedLock = distributedLock;
        this.jobRepository = jobRepository;
        this.notificationRepository = notificationRepository;
        this.scheduledJobRepository = scheduledJobRepository;
        this.templateResolver = templateResolver;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.idempotencyTtl = properties.handler().createdIdempotencyTtl();
        this.responseTimezone = parseZoneId(responseTimezoneStr);
        this.applicationTimezone = ZoneId.of("Asia/Seoul");
    }

    private ZoneId parseZoneId(String zoneStr) {
        try {
            // "Asia/Seoul" 형식
            return ZoneId.of(zoneStr);
        } catch (Exception e) {
            // "+09:00" 형식 호환성 유지
            try {
                return ZoneOffset.of(zoneStr).normalized();
            } catch (Exception ex) {
                return ZoneId.of("Asia/Seoul");
            }
        }
    }

    @Observed(name = "notification.job.create-scheduled")
    public CreateNotificationJobUseCaseOut execute(CreateNotificationJobUseCaseIn useCaseIn) {
        if (useCaseIn.scheduledAt() == null
                || !useCaseIn.scheduledAt().isAfter(OffsetDateTime.now(applicationTimezone))) {
            throw new IllegalArgumentException("예약 발송은 현재 시간 이후의 scheduledAt이 필요합니다.");
        }

        String idempotencyKey = useCaseIn.idempotencyKey();
        if (!idempotencyStore.isIssued(idempotencyKey)) {
            throw new IllegalArgumentException(
                    "발급되지 않은 멱등성 키입니다. IssueCreationJobKeyUseCase를 통해 키를 먼저 발급받아야 합니다: "
                            + idempotencyKey);
        }

        Optional<String> lockToken = distributedLock.tryLock(idempotencyKey, idempotencyTtl);

        if (lockToken.isEmpty()) {
            log.warn(
                    "[UC:Create] Concurrent creation attempt for idempotencyKey={}",
                    idempotencyKey);
            throw new DuplicateResourceException(
                    "Creation already in progress for key: " + idempotencyKey);
        }

        return transactionTemplate.execute(
                status -> {
                    try {
                        var existing =
                                jobRepository.findByIdempotencyKeyAndDeletedFalse(idempotencyKey);
                        if (existing.isPresent()) {
                            log.info(
                                    "[UC:Create] Job already exists for key={}, returning existing",
                                    idempotencyKey);
                            long count =
                                    notificationRepository.countByJobIdAndDeletedFalse(
                                            existing.get().getId());
                            List<ScheduleRecord> existingScheduleHistory =
                                    scheduledJobRepository
                                            .findAllByJobIdOrderByScheduledAtAsc(
                                                    existing.get().getId())
                                            .stream()
                                            .map(
                                                    s ->
                                                            new ScheduleRecord(
                                                                    s.getType(),
                                                                    s.getScheduledAt()
                                                                            .atZoneSameInstant(
                                                                                    responseTimezone)
                                                                            .toOffsetDateTime(),
                                                                    s.isExecuted()))
                                            .toList();
                            return new CreateNotificationJobUseCaseOut(
                                    toResponse(
                                            existing.get(), (int) count, existingScheduleHistory),
                                    false);
                        }

                        TemplateResolver.ResolvedTemplate templates =
                                templateResolver.resolve(
                                        useCaseIn.templateCode(),
                                        useCaseIn.channel(),
                                        useCaseIn.locale());

                        NotificationJob job =
                                NotificationJob.create(
                                        useCaseIn.channel(),
                                        templates.titleTemplate(),
                                        templates.contentTemplate(),
                                        idempotencyKey,
                                        useCaseIn.type(),
                                        useCaseIn.metadata());
                        jobRepository.save(job);

                        List<Notification> notifications =
                                useCaseIn.recipients().stream()
                                        .map(
                                                r ->
                                                        Notification.create(
                                                                job.getId(),
                                                                r.recipientId(),
                                                                r.contact(),
                                                                r.variables() != null
                                                                        ? r.variables()
                                                                        : Map.of(),
                                                                job.getType(),
                                                                job.getMetadata()))
                                        .toList();
                        notificationRepository.saveAll(notifications);

                        distributedLock.unlock(idempotencyKey, lockToken.get());

                        eventPublisher.publish(
                                new NotificationJobCreatedAfterCommitEvent(
                                        job.getId(),
                                        job.getIdempotencyKey(),
                                        useCaseIn.scheduledAt()));

                        log.info(
                                "[UC:Create] Job {} created (channel={}, recipients={}, scheduledAt={}, key={})",
                                job.getId(),
                                useCaseIn.channel(),
                                notifications.size(),
                                useCaseIn.scheduledAt(),
                                idempotencyKey);

                        List<ScheduleRecord> scheduleHistory =
                                List.of(
                                        new ScheduleRecord(
                                                ScheduleType.INITIAL,
                                                useCaseIn
                                                        .scheduledAt()
                                                        .atZoneSameInstant(responseTimezone)
                                                        .toOffsetDateTime(),
                                                false));
                        return new CreateNotificationJobUseCaseOut(
                                toResponse(job, notifications.size(), scheduleHistory), true);
                    } finally {
                        // TX 내에서 이미 해제했지만, 예외 발생 시 미해제 상태일 수 있으므로 안전하게 재호출
                        distributedLock.unlock(idempotencyKey, lockToken.get());
                    }
                });
    }

    private NotificationJobResponse toResponse(
            NotificationJob job, int notificationCount, List<ScheduleRecord> scheduleHistory) {
        return new NotificationJobResponse(
                job.getId(),
                job.getChannel(),
                job.getStatus(),
                job.getType(),
                job.getMetadata(),
                scheduleHistory,
                notificationCount,
                notificationCount, // 생성 직후 전체 PENDING
                0,
                0,
                0,
                0,
                0,
                0,
                job.getCreatedAt().atZoneSameInstant(responseTimezone).toOffsetDateTime(),
                null);
    }
}
