package com.notification.notification.service.job.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RetryBackoffCalculator — 지수 백오프 계산")
class RetryBackoffCalculatorTest {

    private static final Duration BASE_DELAY = Duration.ofSeconds(30);
    private static final Duration MAX_DELAY = Duration.ofHours(1);

    @Nested
    @DisplayName("jitterRatio=0 (결정적 계산)")
    class NoJitter {

        private final RetryBackoffCalculator calculator =
                new RetryBackoffCalculator(BASE_DELAY, MAX_DELAY, 0.0);

        @Test
        @DisplayName("sendTryCount=1 → baseDelay (30초)")
        void first_attempt_returns_base_delay() {
            OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

            OffsetDateTime result = calculator.calculate(1);

            OffsetDateTime expectedMin = before.plusSeconds(30);
            assertThat(result).isAfterOrEqualTo(expectedMin);
            assertThat(result).isBefore(expectedMin.plusSeconds(2)); // 실행 시간 오차 허용
        }

        @Test
        @DisplayName("sendTryCount=2 → baseDelay × 2 (60초)")
        void second_attempt_doubles_delay() {
            OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

            OffsetDateTime result = calculator.calculate(2);

            OffsetDateTime expectedMin = before.plusSeconds(60);
            assertThat(result).isAfterOrEqualTo(expectedMin);
            assertThat(result).isBefore(expectedMin.plusSeconds(2));
        }

        @Test
        @DisplayName("sendTryCount=3 → baseDelay × 4 (120초)")
        void third_attempt_quadruples_delay() {
            OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

            OffsetDateTime result = calculator.calculate(3);

            OffsetDateTime expectedMin = before.plusSeconds(120);
            assertThat(result).isAfterOrEqualTo(expectedMin);
            assertThat(result).isBefore(expectedMin.plusSeconds(2));
        }

        @Test
        @DisplayName("maxDelay를 초과하면 maxDelay로 클램프된다")
        void delay_is_clamped_to_max_delay() {
            // sendTryCount=12 → 30 × 2^11 = 61,440초 > 3,600초(maxDelay)
            OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

            OffsetDateTime result = calculator.calculate(12);

            OffsetDateTime expectedMin = before.plusSeconds(MAX_DELAY.getSeconds());
            assertThat(result).isAfterOrEqualTo(expectedMin);
            assertThat(result).isBefore(expectedMin.plusSeconds(2));
        }
    }

    @Nested
    @DisplayName("jitterRatio > 0")
    class WithJitter {

        private final RetryBackoffCalculator calculator =
                new RetryBackoffCalculator(BASE_DELAY, MAX_DELAY, 0.25);

        @Test
        @DisplayName("jitter 적용 시 결과가 ±25% 범위 내에 있다")
        void result_within_jitter_range() {
            long baseDelaySeconds = BASE_DELAY.getSeconds(); // 30
            long minExpected = (long) (baseDelaySeconds * (1 - 0.25)); // 22.5 → 22
            long maxExpected = (long) (baseDelaySeconds * (1 + 0.25)); // 37.5 → 37

            OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

            // 여러 번 실행하여 범위 내 값인지 확인
            for (int i = 0; i < 20; i++) {
                OffsetDateTime result = calculator.calculate(1);
                long actualSeconds = Duration.between(before, result).getSeconds();

                assertThat(actualSeconds)
                        .as("jitter 적용 결과가 범위 내여야 합니다: %d초", actualSeconds)
                        .isBetween(minExpected - 1, maxExpected + 2); // 실행 시간 오차 허용
            }
        }

        @Test
        @DisplayName("jitter 적용 후에도 maxDelay를 초과하지 않는다")
        void jitter_does_not_exceed_max_delay() {
            for (int i = 0; i < 20; i++) {
                OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
                OffsetDateTime result = calculator.calculate(12);
                long actualSeconds = Duration.between(before, result).getSeconds();

                assertThat(actualSeconds).isLessThanOrEqualTo(MAX_DELAY.getSeconds() + 1);
            }
        }

        @Test
        @DisplayName("jitter 적용 후 최소 1초 이상이다")
        void jitter_result_at_least_one_second() {
            // 매우 작은 baseDelay에서도 최소 1초 보장
            RetryBackoffCalculator smallCalc =
                    new RetryBackoffCalculator(Duration.ofSeconds(2), MAX_DELAY, 0.9);

            for (int i = 0; i < 20; i++) {
                OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
                OffsetDateTime result = smallCalc.calculate(1);
                long actualSeconds = Duration.between(before, result).getSeconds();

                assertThat(actualSeconds).isGreaterThanOrEqualTo(1);
            }
        }
    }
}
