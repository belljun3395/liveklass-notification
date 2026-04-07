package com.notification.notification.service.job.failure;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ThreadLocalRandom;

public class RetryBackoffCalculator {

    private final Duration baseDelay;
    private final Duration maxDelay;
    private final double jitterRatio;

    public RetryBackoffCalculator(Duration baseDelay, Duration maxDelay, double jitterRatio) {
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.jitterRatio = jitterRatio;
    }

    /**
     * 재시도 백오프 시각을 계산합니다.
     *
     * <p>{@code nextRetryAt = now + clamp(baseDelay × 2^(sendTryCount - 1) ± jitter, 1s,
     * maxDelay)}. jitter는 {@code jitterRatio > 0}일 때만 적용됩니다.
     */
    public OffsetDateTime calculate(int sendTryCount) {
        long delaySeconds = baseDelay.getSeconds() * (1L << (sendTryCount - 1));
        delaySeconds = Math.min(delaySeconds, maxDelay.getSeconds());
        if (jitterRatio > 0.0) {
            long jitterBound = (long) (delaySeconds * jitterRatio);
            long jitter = (long) (jitterBound * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
            delaySeconds = Math.max(1L, delaySeconds + jitter);
            delaySeconds = Math.min(delaySeconds, maxDelay.getSeconds());
        }
        return OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(delaySeconds);
    }
}
