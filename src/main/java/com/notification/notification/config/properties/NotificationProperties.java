package com.notification.notification.config.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
        String responseTimezone,
        Relay relay,
        Handler handler,
        Retry retry,
        Batch batch,
        StuckRecovery stuckRecovery,
        Metrics metrics) {

    public NotificationProperties {
        if (responseTimezone == null) responseTimezone = "Asia/Seoul";
        if (relay == null) relay = new Relay(null, null, null, null);
        if (handler == null) handler = new Handler(null, null);
        if (retry == null) retry = new Retry(0, null, null, 0.0);
        if (batch == null) batch = new Batch(0);
        if (stuckRecovery == null) stuckRecovery = new StuckRecovery(0, 0, 0);
        if (metrics == null) metrics = new Metrics(0);
    }

    public record Relay(
            Schedule schedule,
            RetrySchedule retrySchedule,
            Execution execution,
            Retrying retrying) {
        public Relay {
            if (schedule == null) schedule = new Schedule(0, 0);
            if (retrySchedule == null) retrySchedule = new RetrySchedule(0, 0);
            if (execution == null) execution = new Execution(0, 0);
            if (retrying == null) retrying = new Retrying(0, 0);
        }

        public record Schedule(long fixedDelayMs, int batchSize) {
            public Schedule {
                if (fixedDelayMs <= 0) fixedDelayMs = 10_000;
                if (batchSize <= 0) batchSize = 50;
            }
        }

        public record RetrySchedule(long fixedDelayMs, int batchSize) {
            public RetrySchedule {
                if (fixedDelayMs <= 0) fixedDelayMs = 10_000;
                if (batchSize <= 0) batchSize = 20;
            }
        }

        public record Execution(long fixedDelayMs, int batchSize) {
            public Execution {
                if (fixedDelayMs <= 0) fixedDelayMs = 5_000;
                if (batchSize <= 0) batchSize = 50;
            }
        }

        public record Retrying(long fixedDelayMs, int batchSize) {
            public Retrying {
                if (fixedDelayMs <= 0) fixedDelayMs = 5_000;
                if (batchSize <= 0) batchSize = 20;
            }
        }
    }

    public record Handler(Duration createdIdempotencyTtl, Duration executionIdempotencyTtl) {
        public Handler {
            if (createdIdempotencyTtl == null) createdIdempotencyTtl = Duration.ofMinutes(10);
            if (executionIdempotencyTtl == null) executionIdempotencyTtl = Duration.ofMinutes(30);
        }
    }

    public record Retry(
            int maxSendTryCount, Duration baseDelay, Duration maxDelay, double jitterRatio) {
        public Retry {
            if (maxSendTryCount <= 0) maxSendTryCount = 5;
            if (baseDelay == null) baseDelay = Duration.ofSeconds(30);
            if (maxDelay == null) maxDelay = Duration.ofHours(1);
            if (jitterRatio < 0.0 || jitterRatio >= 1.0) jitterRatio = 0.0;
        }
    }

    public record Batch(int size) {
        public Batch {
            if (size <= 0) size = 200;
        }
    }

    public record StuckRecovery(long fixedDelayMs, int batchSize, long stuckTimeoutSeconds) {
        public StuckRecovery {
            if (fixedDelayMs <= 0) fixedDelayMs = 60_000;
            if (batchSize <= 0) batchSize = 50;
            if (stuckTimeoutSeconds <= 0) stuckTimeoutSeconds = 300;
        }
    }

    public record Metrics(long pollIntervalMs) {
        public Metrics {
            if (pollIntervalMs <= 0) pollIntervalMs = 10_000;
        }
    }
}
