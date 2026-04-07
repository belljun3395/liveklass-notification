package com.notification.config.email;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MockEmailSender 의 동작 파라미터를 보관하고 런타임에 변경을 허용하는 컨트롤 객체.
 *
 * <p>{@code @ConfigurationProperties(prefix = "mock.email")} 를 통해 application.yml 의 {@code
 * mock.email.*} 값으로 초기화됩니다. MockEmailConfig 에서 {@code @EnableConfigurationProperties} 로 활성화합니다.
 *
 * <p><b>스레드 안전성:</b><br>
 * 모든 필드를 {@link AtomicReference} / {@link AtomicInteger} 로 선언한 이유는, {@code
 * MockEmailSenderEndpoint}(HTTP 스레드)와 {@code MockEmailSender}(Virtual Thread 풀)가 동시에 읽기/쓰기를 수행하기
 * 때문입니다. Atomic 타입을 사용하면 별도의 {@code synchronized} 블록 없이 단일 필드 단위의 원자적 갱신을 보장합니다.
 *
 * <p><b>파라미터 기본값 및 의미:</b>
 *
 * <pre>
 * failRate      = 0.05  → 5% 확률로 발송 실패 시뮬레이션
 * failType      = TRANSIENT → 재시도 가능한 일시적 오류
 * latencyMs     = 0     → 기본 지연 없음 (0이면 10~100ms 랜덤 지연 적용)
 * hangRate      = 0.0   → hang 미발생
 * hangTimeoutMs = 30000 → hang 발생 시 30초 대기
 * </pre>
 */
@ConfigurationProperties(prefix = "mock.email")
public class MockEmailSenderControl {

    private final AtomicReference<Double> failRate = new AtomicReference<>(0.05);
    private final AtomicReference<String> failType = new AtomicReference<>("TRANSIENT");
    private final AtomicInteger latencyMs = new AtomicInteger(0);
    private final AtomicReference<Double> hangRate = new AtomicReference<>(0.0);
    private final AtomicInteger hangTimeoutMs = new AtomicInteger(30_000);

    public void setFailRate(double failRate) {
        this.failRate.set(failRate);
    }

    public void setFailType(String failType) {
        this.failType.set(failType);
    }

    public void setLatencyMs(int latencyMs) {
        this.latencyMs.set(latencyMs);
    }

    public void setHangRate(double hangRate) {
        this.hangRate.set(hangRate);
    }

    public void setHangTimeoutMs(int hangTimeoutMs) {
        this.hangTimeoutMs.set(hangTimeoutMs);
    }

    public double getFailRate() {
        return failRate.get();
    }

    public String getFailType() {
        return failType.get();
    }

    public int getLatencyMs() {
        return latencyMs.get();
    }

    public double getHangRate() {
        return hangRate.get();
    }

    public int getHangTimeoutMs() {
        return hangTimeoutMs.get();
    }

    /**
     * 파라미터를 부분적으로 갱신합니다. null 로 전달된 항목은 기존 값을 유지합니다. MockEmailSenderEndpoint 의 WriteOperation 에서
     * 호출됩니다.
     */
    public void update(
            Double newFailRate,
            String newFailType,
            Integer newLatencyMs,
            Double newHangRate,
            Integer newHangTimeoutMs) {
        if (newFailRate != null) this.failRate.set(newFailRate);
        if (newFailType != null) this.failType.set(newFailType);
        if (newLatencyMs != null) this.latencyMs.set(newLatencyMs);
        if (newHangRate != null) this.hangRate.set(newHangRate);
        if (newHangTimeoutMs != null) this.hangTimeoutMs.set(newHangTimeoutMs);
    }

    /**
     * 특정 시점의 설정 상태를 불변 객체로 캡처한 스냅샷.
     *
     * <p>Actuator 의 Read/Write 응답 바디로 직렬화됩니다. AtomicReference 를 직접 직렬화하면 내부 구조가 노출될 수 있으므로, 단순 값
     * 타입으로 복사하여 반환합니다.
     */
    public static class Snapshot {
        private final double failRate;
        private final String failType;
        private final int latencyMs;
        private final double hangRate;
        private final int hangTimeoutMs;

        public Snapshot(MockEmailSenderControl c) {
            this.failRate = c.getFailRate();
            this.failType = c.getFailType();
            this.latencyMs = c.getLatencyMs();
            this.hangRate = c.getHangRate();
            this.hangTimeoutMs = c.getHangTimeoutMs();
        }

        public double getFailRate() {
            return failRate;
        }

        public String getFailType() {
            return failType;
        }

        public int getLatencyMs() {
            return latencyMs;
        }

        public double getHangRate() {
            return hangRate;
        }

        public int getHangTimeoutMs() {
            return hangTimeoutMs;
        }
    }
}
