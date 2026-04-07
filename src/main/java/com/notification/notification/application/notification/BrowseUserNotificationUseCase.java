package com.notification.notification.application.notification;

import com.notification.notification.application.notification.dto.BrowseUserNotificationUseCaseIn;
import com.notification.notification.application.notification.dto.CursorPage;
import com.notification.notification.application.notification.dto.NotificationResponse;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationContent;
import com.notification.notification.domain.NotificationJob;
import com.notification.notification.repository.job.NotificationJobRepository;
import com.notification.notification.repository.notification.NotificationContentRepository;
import com.notification.notification.repository.notification.NotificationRepository;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BrowseUserNotificationUseCase {

    private final NotificationRepository notificationRepository;
    private final NotificationJobRepository jobRepository;
    private final NotificationContentRepository contentRepository;
    private final ZoneOffset responseTimezone;

    public BrowseUserNotificationUseCase(
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
    public CursorPage<NotificationResponse> execute(BrowseUserNotificationUseCaseIn useCaseIn) {
        Long userId = useCaseIn.userId();
        long effectiveCursor = useCaseIn.cursorId() != null ? useCaseIn.cursorId() : Long.MAX_VALUE;
        // size+1개 조회해서 다음 페이지 존재 여부 판별
        PageRequest pageable = PageRequest.of(0, useCaseIn.size() + 1);

        List<Notification> fetched =
                switch (useCaseIn.readFilter()) {
                    case null ->
                            notificationRepository
                                    .findByRecipientIdAndIdLessThanAndDeletedFalseOrderByIdDesc(
                                            userId, effectiveCursor, pageable);
                    case Boolean b when b ->
                            notificationRepository
                                    .findByRecipientIdAndFirstReadAtIsNotNullAndIdLessThanAndDeletedFalseOrderByIdDesc(
                                            userId, effectiveCursor, pageable);
                    case Boolean b ->
                            notificationRepository
                                    .findByRecipientIdAndFirstReadAtIsNullAndIdLessThanAndDeletedFalseOrderByIdDesc(
                                            userId, effectiveCursor, pageable);
                };

        boolean hasNext = fetched.size() > useCaseIn.size();
        List<Notification> items = hasNext ? fetched.subList(0, useCaseIn.size()) : fetched;

        Set<Long> notificationIds =
                items.stream().map(Notification::getId).collect(Collectors.toSet());
        Set<Long> jobIds = items.stream().map(Notification::getJobId).collect(Collectors.toSet());

        Map<Long, NotificationJob> jobMap =
                jobRepository.findAllByIdInAndDeletedFalse(jobIds).stream()
                        .collect(Collectors.toMap(NotificationJob::getId, Function.identity()));
        Map<Long, NotificationContent> contentMap =
                contentRepository.findAllByNotificationIdIn(notificationIds).stream()
                        .collect(
                                Collectors.toMap(
                                        NotificationContent::getNotificationId,
                                        Function.identity()));

        List<NotificationResponse> responseItems =
                items.stream()
                        .map(
                                n -> {
                                    NotificationJob job = jobMap.get(n.getJobId());
                                    NotificationContent content = contentMap.get(n.getId());
                                    String renderedTitle =
                                            content != null ? content.getRenderedTitle() : null;
                                    String renderedBody =
                                            content != null ? content.getRenderedBody() : null;
                                    return NotificationResponse.from(
                                            n,
                                            job.getChannel(),
                                            renderedTitle,
                                            renderedBody,
                                            responseTimezone);
                                })
                        .toList();

        Long nextCursor = hasNext ? items.get(items.size() - 1).getId() : null;
        return new CursorPage<>(responseItems, nextCursor, hasNext);
    }
}
