package com.notification.config.email;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * MockEmailSender 의 런타임 동작을 제어하는 Spring Actuator 커스텀 엔드포인트.
 *
 * <p>이 클래스는 Java 코드에서 직접 참조되지 않지만, Spring Boot Actuator 가 {@code @Endpoint} + {@code @Component}
 * 조합을 자동으로 감지하여 {@code /actuator/mock-email} HTTP 경로에 등록합니다. application.yml 의 {@code
 * management.endpoints.web.exposure.include} 에 {@code mock-email} 이 포함되어 있어야 외부에서 접근할 수 있습니다.
 *
 * <p><b>주 사용처: 부하 테스트 (load-test/scripts/lib/helpers.js)</b><br>
 * k6 스크립트가 시나리오 전환 시 이 엔드포인트를 호출하여 애플리케이션 재시작 없이 이메일 발송 동작(실패율, 지연, hang 등)을 동적으로 주입합니다.
 *
 * <p><b>엔드포인트 사용법:</b>
 *
 * <pre>
 * // 현재 설정 조회
 * GET /actuator/mock-email
 *
 * // 런타임에 설정 변경 (모든 파라미터 선택적)
 * POST /actuator/mock-email
 * Content-Type: application/json
 * {
 *   "failRate": 0.3,        // 실패 확률 (0.0 ~ 1.0)
 *   "failType": "TRANSIENT", // 실패 유형: TRANSIENT | PERMANENT
 *   "latencyMs": 200,        // 응답 지연 (ms)
 *   "hangRate": 0.05,        // hang 발생 확률 (0.0 ~ 1.0)
 *   "hangTimeoutMs": 30000   // hang 지속 시간 (ms)
 * }
 * </pre>
 *
 * <p><b>주의:</b> {@code @Profile("!prod")} 로 인해 prod 프로파일에서는 빈이 등록되지 않으므로 운영 환경에 노출될 위험이 없습니다.
 */
@Component
@Profile("!prod")
@Endpoint(id = "mock-email")
public class MockEmailSenderEndpoint {

    private final MockEmailSenderControl control;

    public MockEmailSenderEndpoint(MockEmailSenderControl control) {
        this.control = control;
    }

    /** 현재 MockEmailSender 설정의 스냅샷을 반환합니다. HTTP GET /actuator/mock-email 에 매핑됩니다. */
    @ReadOperation
    public MockEmailSenderControl.Snapshot read() {
        return new MockEmailSenderControl.Snapshot(control);
    }

    /**
     * MockEmailSender 의 동작 파라미터를 런타임에 변경합니다. HTTP POST /actuator/mock-email 에 매핑됩니다.
     *
     * <p>모든 파라미터는 선택적(Nullable)이며, null 로 전달된 항목은 기존 값을 유지합니다. 변경 후 적용된 상태의 스냅샷을 즉시 반환합니다.
     *
     * @param failRate 이메일 발송 실패 확률 (0.0 ~ 1.0). null 이면 기존 값 유지.
     * @param failType 실패 유형 ("TRANSIENT" 또는 "PERMANENT"). null 이면 기존 값 유지.
     * @param latencyMs 발송 지연 시간 (ms). null 이면 기존 값 유지.
     * @param hangRate hang 발생 확률 (0.0 ~ 1.0). null 이면 기존 값 유지.
     * @param hangTimeoutMs hang 지속 시간 (ms). null 이면 기존 값 유지.
     * @return 변경 적용 후 현재 설정 스냅샷
     */
    @WriteOperation
    public MockEmailSenderControl.Snapshot write(
            @Nullable Double failRate,
            @Nullable String failType,
            @Nullable Integer latencyMs,
            @Nullable Double hangRate,
            @Nullable Integer hangTimeoutMs) {
        control.update(failRate, failType, latencyMs, hangRate, hangTimeoutMs);
        return new MockEmailSenderControl.Snapshot(control);
    }
}
