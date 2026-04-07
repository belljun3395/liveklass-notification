package com.notification.config.event;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EventPublicationCleanupScheduler {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final CompletedEventPublications completedPublications;

    public EventPublicationCleanupScheduler(CompletedEventPublications completedPublications) {
        this.completedPublications = completedPublications;
    }

    // 매일 새벽 3시
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanup() {
        log.info("event_publication 완료 레코드 정리 시작 ({}일 초과)", RETENTION.toDays());
        completedPublications.deletePublicationsOlderThan(RETENTION);
        log.info("event_publication 완료 레코드 정리 완료");
    }
}
