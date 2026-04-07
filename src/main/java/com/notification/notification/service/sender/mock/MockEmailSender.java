package com.notification.notification.service.sender.mock;

import com.notification.config.email.MockEmailSenderControl;
import com.notification.notification.domain.FailureClassification;
import com.notification.notification.domain.NotificationChannel;
import com.notification.notification.service.sender.NotificationService;
import io.micrometer.observation.annotation.Observed;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 실제 이메일 서버 없이 이메일 발송을 시뮬레이션하는 Mock 구현체.
 *
 * <p>로컬/개발/부하 테스트 환경에서 실제 SMTP 연동 없이 이메일 발송 파이프라인을 검증할 수 있도록 합니다. 동작 파라미터는 {@link
 * MockEmailSenderControl} 을 통해 런타임에 변경할 수 있습니다 (→ {@code /actuator/mock-email}).
 *
 * <p><b>시뮬레이션 동작:</b>
 *
 * <ul>
 *   <li>지연(latency): 고정 ms 또는 기본 10~100ms 랜덤 지연
 *   <li>hang: 설정된 확률로 {@code hangTimeoutMs} 동안 블로킹
 *   <li>실패(fail): 설정된 확률로 TRANSIENT 또는 PERMANENT 실패 반환
 * </ul>
 *
 * <p><b>Virtual Thread 사용 이유:</b><br>
 * 실제 이메일 서버처럼 I/O 대기가 발생하는 상황을 시뮬레이션하기 위해 {@link Executors#newVirtualThreadPerTaskExecutor()} 를
 * 사용합니다. 수신자 수만큼 Virtual Thread 를 생성하여 병렬로 발송하며, 부하 테스트에서 Virtual Thread 기반 동시성 처리를 검증하는 역할도 합니다.
 */
@Slf4j
@Profile("!prod")
@Component
public class MockEmailSender implements NotificationService {

    private final MockEmailSenderControl control;

    public MockEmailSender(MockEmailSenderControl control) {
        this.control = control;
    }

    @Override
    public NotificationChannel supportedChannel() {
        return NotificationChannel.EMAIL;
    }

    /**
     * 페이로드 목록을 Virtual Thread 로 병렬 발송합니다.
     *
     * <p>각 수신자마다 별도의 Virtual Thread 를 생성하여 {@link #sendOne}을 호출합니다. try-with-resources 로
     * ExecutorService 를 선언하면 모든 작업 완료 후 자동으로 shutdown 되므로, Future.get() 전에 스레드가 종료될 걱정이 없습니다.
     *
     * @param payloads 발송할 알림 페이로드 목록
     * @return 각 페이로드에 대응하는 발송 결과 목록 (순서 보장)
     */
    @Observed(name = "notification.email.send_batch")
    @Override
    public List<SendResult> send(List<SendPayload> payloads) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SendResult>> futures = new ArrayList<>(payloads.size());
            for (SendPayload payload : payloads) {
                futures.add(executor.submit(() -> sendOne(payload)));
            }

            List<SendResult> results = new ArrayList<>(payloads.size());
            for (Future<SendResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return payloads.stream()
                    .map(
                            p ->
                                    SendResult.fail(
                                            p.notificationId(),
                                            "virtual thread 실행 오류",
                                            FailureClassification.TRANSIENT))
                    .toList();
        }
    }

    /**
     * 단일 수신자에 대한 발송을 시뮬레이션합니다.
     *
     * <p>실행 순서:
     *
     * <ol>
     *   <li>{@link #applyLatency()} — 지연/hang 적용
     *   <li>failRate 확률로 실패 결과 반환 (failType 에 따라 분류 결정)
     *   <li>성공 결과 반환
     * </ol>
     */
    private SendResult sendOne(SendPayload payload) {
        try {
            applyLatency();

            double failRate = control.getFailRate();
            if (ThreadLocalRandom.current().nextDouble() < failRate) {
                FailureClassification classification =
                        "PERMANENT".equalsIgnoreCase(control.getFailType())
                                ? FailureClassification.PERMANENT
                                : FailureClassification.TRANSIENT;
                String reason =
                        classification == FailureClassification.PERMANENT
                                ? "유효하지 않은 수신자 주소 (MOCK PERMANENT)"
                                : "네트워크 타임아웃 (MOCK TRANSIENT)";
                return SendResult.fail(payload.notificationId(), reason, classification);
            }

            log.debug(
                    "[MOCK EMAIL] sent to={} title={}",
                    payload.recipientContact(),
                    payload.renderedTitle());
            return SendResult.success(payload.notificationId());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.fail(
                    payload.notificationId(), "인터럽트", FailureClassification.TRANSIENT);
        }
    }

    /**
     * 설정에 따라 발송 지연 또는 hang 을 시뮬레이션합니다.
     *
     * <p>우선순위:
     *
     * <ol>
     *   <li>hangRate 확률에 해당하면 {@code hangTimeoutMs} 동안 블로킹 후 반환 (실제 외부 서버가 응답하지 않는 hang 상황 재현)
     *   <li>latencyMs > 0 이면 해당 시간만큼 고정 지연
     *   <li>latencyMs = 0 이면 10~100ms 범위의 랜덤 지연 (기본 네트워크 지연 모사)
     * </ol>
     */
    private void applyLatency() throws InterruptedException {
        int latencyMs = control.getLatencyMs();

        double hangRate = control.getHangRate();
        if (hangRate > 0 && ThreadLocalRandom.current().nextDouble() < hangRate) {
            Thread.sleep(control.getHangTimeoutMs());
            return;
        }

        if (latencyMs > 0) {
            Thread.sleep(latencyMs);
        } else {
            Thread.sleep(ThreadLocalRandom.current().nextInt(10, 100));
        }
    }
}
