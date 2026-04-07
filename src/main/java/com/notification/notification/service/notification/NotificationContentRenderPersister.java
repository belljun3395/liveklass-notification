package com.notification.notification.service.notification;

import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationContent;
import com.notification.notification.port.TemplateRenderer;
import com.notification.notification.repository.notification.NotificationContentRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NotificationContentRenderPersister {

    private final NotificationContentRepository contentRepository;
    private final TemplateRenderer templateRenderer;

    public NotificationContentRenderPersister(
            NotificationContentRepository contentRepository, TemplateRenderer templateRenderer) {
        this.contentRepository = contentRepository;
        this.templateRenderer = templateRenderer;
    }

    @Transactional
    public Map<Long, NotificationContent> execute(
            String titleTemplate, String contentTemplate, List<Notification> notifications) {
        List<NotificationContent> contents =
                notifications.stream()
                        .map(
                                n -> {
                                    String title =
                                            templateRenderer.render(
                                                    titleTemplate, n.getVariables());
                                    String body =
                                            templateRenderer.render(
                                                    contentTemplate, n.getVariables());
                                    return NotificationContent.create(n.getId(), title, body);
                                })
                        .toList();
        contentRepository.saveAll(contents);
        return contents.stream()
                .collect(
                        Collectors.toMap(
                                NotificationContent::getNotificationId, Function.identity()));
    }
}
