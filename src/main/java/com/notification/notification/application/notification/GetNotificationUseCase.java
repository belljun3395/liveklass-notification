package com.notification.notification.application.notification;

import com.notification.notification.application.notification.dto.NotificationResponse;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationContent;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.repository.job.NotificationJobRepository;
import com.notification.notification.repository.notification.NotificationContentRepository;
import com.notification.notification.repository.notification.NotificationRepository;
import com.notification.support.web.exception.ResourceNotFoundException;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class GetNotificationUseCase {

    private final NotificationRepository notificationRepository;
    private final NotificationJobRepository jobRepository;
    private final NotificationContentRepository contentRepository;
    private final ZoneOffset responseTimezone;

    public GetNotificationUseCase(
            NotificationRepository notificationRepository,
            NotificationJobRepository jobRepository,
            NotificationContentRepository contentRepository,
            @Value("${notification.response-timezone:+09:00}") ZoneOffset responseTimezone) {
        this.notificationRepository = notificationRepository;
        this.jobRepository = jobRepository;
        this.contentRepository = contentRepository;
        this.responseTimezone = responseTimezone;
    }

    @Transactional(readOnly = true)
    public NotificationResponse execute(Long notificationId) {
        Notification notification =
                notificationRepository
                        .findByIdAndDeletedFalse(notificationId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "Notification not found: " + notificationId));
        NotificationJob job =
                jobRepository.findByIdAndDeletedFalse(notification.getJobId()).orElseThrow();

        Optional<NotificationContent> content =
                contentRepository.findByNotificationId(notificationId);
        String renderedTitle = content.map(NotificationContent::getRenderedTitle).orElse(null);
        String renderedBody = content.map(NotificationContent::getRenderedBody).orElse(null);

        return NotificationResponse.from(
                notification, job.getChannel(), renderedTitle, renderedBody, responseTimezone);
    }
}
