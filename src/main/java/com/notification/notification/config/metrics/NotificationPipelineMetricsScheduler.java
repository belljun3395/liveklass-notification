package com.notification.notification.config.metrics;

import com.notification.notification.domain.NotificationStatus;
import com.notification.notification.repository.job.NotificationJobRepository;
import com.notification.notification.repository.notification.NotificationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 알림 파이프라인 메트릭을 주기적으로 DB에서 폴링하여 갱신합니다.
 *
 * <p>비즈니스 코드와 메트릭 수집을 완전히 분리하기 위해 DB 상태 기반으로 메트릭을 산출합니다. 폴링 주기는 {@code
 * notification.metrics.poll-interval-ms}로 설정합니다.
 */
@Slf4j
@Component
public class NotificationPipelineMetricsScheduler {

    private final NotificationRepository notificationRepository;
    private final NotificationJobRepository jobRepository;

    // ── 단조증가 카운터용 이전 값 ────────────────────────────────
    private final Counter jobCounter;
    private final Counter notificationCounter;
    private final Counter sentCounter;
    private final Counter deadLetterCounter;
    private long prevJobCount = -1;
    private long prevNotificationCount = -1;
    private long prevSentCount = -1;
    private long prevDeadLetterCount = -1;

    // ── Gauge 값 ─────────────────────────────────────────────────
    private final AtomicLong backlogDepth = new AtomicLong(0);
    private final AtomicLong sendingDepth = new AtomicLong(0);
    private final AtomicLong retryWaitingDepth = new AtomicLong(0);

    public NotificationPipelineMetricsScheduler(
            NotificationRepository notificationRepository,
            NotificationJobRepository jobRepository,
            MeterRegistry registry) {
        this.notificationRepository = notificationRepository;
        this.jobRepository = jobRepository;

        this.jobCounter =
                Counter.builder("notification.job").description("수락된 Job 누적 수").register(registry);

        this.notificationCounter =
                Counter.builder("notification")
                        .description("생성된 Notification 누적 수")
                        .register(registry);

        this.sentCounter =
                Counter.builder("notification.sent").description("발송 성공 누적 건수").register(registry);

        this.deadLetterCounter =
                Counter.builder("notification.dead_letter")
                        .description("Dead-letter 종결 누적 건수")
                        .register(registry);

        Gauge.builder("notification.backlog.depth", backlogDepth, AtomicLong::get)
                .description("PENDING 상태 Notification 잔량")
                .register(registry);

        Gauge.builder("notification.sending.depth", sendingDepth, AtomicLong::get)
                .description("SENDING 상태 Notification 잔량")
                .register(registry);

        Gauge.builder("notification.retry_waiting.depth", retryWaitingDepth, AtomicLong::get)
                .description("RETRY_WAITING 상태 Notification 잔량")
                .register(registry);
    }

    @Scheduled(
            fixedDelayString = "${notification.metrics.poll-interval-ms:10000}",
            initialDelayString = "${notification.metrics.poll-interval-ms:10000}")
    public void poll() {
        try {
            long jobCount = jobRepository.count();
            long notificationCount = notificationRepository.count();
            long sentCount =
                    notificationRepository.countByStatusAndDeletedFalse(NotificationStatus.SENT);
            long deadLetterCount =
                    notificationRepository.countByStatusAndDeletedFalse(
                            NotificationStatus.DEAD_LETTER);

            incrementIfGrown(jobCounter, jobCount, prevJobCount);
            prevJobCount = jobCount;

            incrementIfGrown(notificationCounter, notificationCount, prevNotificationCount);
            prevNotificationCount = notificationCount;

            incrementIfGrown(sentCounter, sentCount, prevSentCount);
            prevSentCount = sentCount;

            incrementIfGrown(deadLetterCounter, deadLetterCount, prevDeadLetterCount);
            prevDeadLetterCount = deadLetterCount;

            backlogDepth.set(
                    notificationRepository.countByStatusAndDeletedFalse(
                            NotificationStatus.PENDING));
            sendingDepth.set(
                    notificationRepository.countByStatusAndDeletedFalse(
                            NotificationStatus.SENDING));
            retryWaitingDepth.set(
                    notificationRepository.countByStatusAndDeletedFalse(
                            NotificationStatus.RETRY_WAITING));
        } catch (Exception e) {
            log.error("파이프라인 메트릭 폴링 중 오류", e);
        }
    }

    private void incrementIfGrown(Counter counter, long current, long previous) {
        if (previous >= 0 && current > previous) {
            counter.increment(current - previous);
        }
    }
}
