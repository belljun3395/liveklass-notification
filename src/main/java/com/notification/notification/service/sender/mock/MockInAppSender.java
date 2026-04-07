package com.notification.notification.service.sender.mock;

import com.notification.notification.domain.NotificationChannel;
import com.notification.notification.service.sender.NotificationService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Profile("!prod")
@Component
public class MockInAppSender implements NotificationService {

    @Override
    public NotificationChannel supportedChannel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public List<SendResult> send(List<SendPayload> payloads) {
        return payloads.stream()
                .map(
                        payload -> {
                            log.info(
                                    "[MOCK IN_APP] to={}, title={}",
                                    payload.recipientContact(),
                                    payload.renderedTitle());
                            return SendResult.success(payload.notificationId());
                        })
                .toList();
    }
}
