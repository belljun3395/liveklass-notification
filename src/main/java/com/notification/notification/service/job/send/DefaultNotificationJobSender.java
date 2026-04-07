package com.notification.notification.service.job.send;

import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationContent;
import com.notification.notification.repository.notification.NotificationContentRepository;
import com.notification.notification.service.notification.NotificationContentRenderPersister;
import com.notification.notification.service.sender.NotificationSendExecutor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DefaultNotificationJobSender implements NotificationJobSender {

    private final NotificationContentRepository contentRepository;
    private final NotificationContentRenderPersister contentRenderPersister;
    private final NotificationSendExecutor sendExecutor;

    public DefaultNotificationJobSender(
            NotificationContentRepository contentRepository,
            NotificationContentRenderPersister contentRenderPersister,
            NotificationSendExecutor sendExecutor) {
        this.contentRepository = contentRepository;
        this.contentRenderPersister = contentRenderPersister;
        this.sendExecutor = sendExecutor;
    }

    @Override
    public SendResult send(JobSendContext context, List<Notification> targets) {
        if (targets.isEmpty()) {
            log.info("[Sender] No sendable notifications for channel {}", context.channel());
            return new SendResult(List.of(), List.of());
        }

        Map<Long, NotificationContent> contentMap = resolveContent(context, targets);
        NotificationSendExecutor.ExecuteResult execResult =
                sendExecutor.execute(context.channel(), targets, contentMap);

        List<FailedSend> failedSends =
                execResult.failed().stream()
                        .map(
                                fd ->
                                        new FailedSend(
                                                fd.notification(),
                                                fd.classification(),
                                                fd.reason(),
                                                fd.failureReasonCode()))
                        .toList();
        return new SendResult(execResult.sent(), failedSends);
    }

    private Map<Long, NotificationContent> resolveContent(
            JobSendContext context, List<Notification> targets) {
        List<Long> notificationIds = targets.stream().map(Notification::getId).toList();

        Map<Long, NotificationContent> result =
                new HashMap<>(
                        contentRepository.findAllByNotificationIdIn(notificationIds).stream()
                                .collect(
                                        Collectors.toMap(
                                                NotificationContent::getNotificationId,
                                                Function.identity())));

        List<Notification> needsRendering =
                targets.stream().filter(n -> !result.containsKey(n.getId())).toList();

        if (!needsRendering.isEmpty()) {
            result.putAll(
                    contentRenderPersister.execute(
                            context.titleTemplate(), context.contentTemplate(), needsRendering));
        }

        return result;
    }
}
