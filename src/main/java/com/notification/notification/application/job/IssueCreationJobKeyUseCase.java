package com.notification.notification.application.job;

import com.notification.infra.port.IdempotencyStore;
import com.notification.notification.application.job.dto.IssueCreationJobKeyUseCaseOut;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 알림 잡 생성 요청 전 멱등성 키를 발급합니다.
 *
 * <p>알림 잡 생성은 {@link CreateScheduledNotificationJobUseCase}가 담당하지만, 생성 요청에 앞서 반드시 이 유즈케이스를 통해 멱등성
 * 키를 발급받아야 합니다. 발급된 키를 생성 요청에 포함함으로써 동일한 요청이 중복 처리되지 않도록 보장합니다.
 *
 * <p><b>2단계 생성 흐름:</b>
 *
 * <pre>
 *   Step 1. IssueCreationJobKeyUseCase.execute()
 *             → idempotencyKey 발급 후 클라이언트에 반환
 *   Step 2. CreateScheduledNotificationJobUseCase.execute(idempotencyKey, ...)
 *             → 발급된 키를 포함하여 Job 생성 요청
 * </pre>
 *
 * <p><b>설계 의도:</b> 키를 서버가 발급함으로써 클라이언트가 임의 키를 사용하는 것을 방지하고, 네트워크 재시도나 클라이언트 중복 호출 등 예측 불가능한 상황에서 동일
 * 요청이 중복 처리되는 것을 원천 차단합니다.
 *
 * @see CreateScheduledNotificationJobUseCase
 */
@Slf4j
@Component
public class IssueCreationJobKeyUseCase {

    private final IdempotencyStore idempotencyStore;

    public IssueCreationJobKeyUseCase(IdempotencyStore idempotencyStore) {
        this.idempotencyStore = idempotencyStore;
    }

    public IssueCreationJobKeyUseCaseOut execute() {
        String key = idempotencyStore.generateKey();
        log.info("[UC:IssueCreationKey] Issued new creation key={}", key);
        return new IssueCreationJobKeyUseCaseOut(key);
    }
}
