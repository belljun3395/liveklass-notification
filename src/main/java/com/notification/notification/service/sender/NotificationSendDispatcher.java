package com.notification.notification.service.sender;

import com.notification.notification.domain.NotificationChannel;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class NotificationSendDispatcher {

    private final Map<NotificationChannel, NotificationService> senderMap;

    public NotificationSendDispatcher(List<NotificationService> senders) {
        this.senderMap =
                senders.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        NotificationService::supportedChannel,
                                        Function.identity()));
    }

    public List<NotificationService.SendResult> dispatch(
            NotificationChannel channel, List<NotificationService.SendPayload> payloads) {
        NotificationService sender = senderMap.get(channel);
        if (sender == null) {
            throw new UnsupportedOperationException("Unsupported channel: " + channel);
        }
        return sender.send(payloads);
    }
}
