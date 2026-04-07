package com.notification.notification.service.job.failure;

import com.notification.notification.config.properties.NotificationProperties;
import com.notification.notification.domain.FailureClassification;
import com.notification.notification.domain.Notification;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DefaultSendFailureClassifier implements SendFailureClassifier {

    private final int maxSendTryCount;
    private final RetryBackoffCalculator backoffCalculator;

    public DefaultSendFailureClassifier(NotificationProperties properties) {
        this.maxSendTryCount = properties.retry().maxSendTryCount();
        this.backoffCalculator =
                new RetryBackoffCalculator(
                        properties.retry().baseDelay(),
                        properties.retry().maxDelay(),
                        properties.retry().jitterRatio());
    }

    @Override
    public ClassificationResult classify(List<Notification> failed) {
        List<Notification> deadLetters =
                failed.stream()
                        .filter(
                                n ->
                                        n.getLastFailureClassification()
                                                        == FailureClassification.PERMANENT
                                                || n.getSendTryCount() >= maxSendTryCount)
                        .toList();

        List<Notification> retryable =
                failed.stream()
                        .filter(
                                n ->
                                        n.getLastFailureClassification()
                                                        != FailureClassification.PERMANENT
                                                && n.getSendTryCount() < maxSendTryCount)
                        .toList();

        // retryable 중 sendTryCount 최대값을 job 재시도 횟수의 대리 지표로 사용하여 백오프 계산
        Optional<OffsetDateTime> nextRetryAt =
                retryable.stream().mapToInt(Notification::getSendTryCount).max().stream()
                        .mapToObj(backoffCalculator::calculate)
                        .findFirst();

        return new ClassificationResult(deadLetters, retryable, nextRetryAt);
    }
}
