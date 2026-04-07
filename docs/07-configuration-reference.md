# 외부 설정 값 레퍼런스

`application.yml`에서 관리하는 외부 설정 값과 코드 내 사용처를 정리한 문서입니다.

---

## notification.* — 알림 파이프라인 설정

`NotificationProperties` record로 바인딩됩니다.

### 공통

| 키                                | 현재 값     | 기본값      | 사용처                                                                                                                             | 설명                 |
|----------------------------------|----------|----------|---------------------------------------------------------------------------------------------------------------------------------|--------------------|
| `notification.response-timezone` | `+09:00` | `+09:00` | `CreateScheduledNotificationJobUseCase`, `GetNotificationJobUseCase`, `GetNotificationUseCase`, `BrowseUserNotificationUseCase` | API 응답 시 타임존 변환 기준 |

### notification.relay.schedule — 예약 스케줄 폴링

`DbScheduledNotificationJobRelay`가 INITIAL 스케줄을 감지하는 주기와 배치 크기입니다.

| 키                               | 현재 값   | 기본값     | 사용처                                          | 설명                     |
|---------------------------------|--------|---------|----------------------------------------------|------------------------|
| `relay.schedule.fixed-delay-ms` | `2000` | `10000` | `DbScheduledNotificationJobRelay` @Scheduled | INITIAL 스케줄 폴링 주기 (ms) |
| `relay.schedule.batch-size`     | `50`   | `50`    | `DbScheduledNotificationJobRelay` 생성자        | 한 번에 가져오는 예약 Job 수     |

### notification.relay.retry-schedule — 재시도 스케줄 폴링

`DbRetryScheduledNotificationRecoverRelay`가 RETRY 스케줄을 감지하는 주기와 배치 크기입니다.

| 키                                     | 현재 값   | 기본값     | 사용처                                                   | 설명                   |
|---------------------------------------|--------|---------|-------------------------------------------------------|----------------------|
| `relay.retry-schedule.fixed-delay-ms` | `2000` | `10000` | `DbRetryScheduledNotificationRecoverRelay` @Scheduled | RETRY 스케줄 폴링 주기 (ms) |
| `relay.retry-schedule.batch-size`     | `20`   | `20`    | `DbRetryScheduledNotificationRecoverRelay` 생성자        | 한 번에 가져오는 재시도 스케줄 수  |

### notification.relay.execution — PROCESSING Job 실행 이벤트 발행

`ProcessingNotificationJobExecutionScheduler`가 PROCESSING 상태 Job을 폴링하는 주기와 배치 크기입니다.

| 키                                | 현재 값    | 기본값    | 사용처                                                      | 설명                               |
|----------------------------------|---------|--------|----------------------------------------------------------|----------------------------------|
| `relay.execution.fixed-delay-ms` | *(미설정)* | `5000` | `ProcessingNotificationJobExecutionScheduler` @Scheduled | PROCESSING Job 실행 이벤트 발행 주기 (ms) |
| `relay.execution.batch-size`     | *(미설정)* | `50`   | `ProcessingNotificationJobExecutionScheduler` 생성자        | 한 번에 가져오는 PROCESSING Job 수       |

### notification.relay.retrying — RETRYING → PROCESSING 전이

`RetryingNotificationJobExecutionScheduler`가 RETRYING 상태 Job을 폴링하는 주기와 배치 크기입니다.

| 키                               | 현재 값    | 기본값    | 사용처                                                    | 설명                                  |
|---------------------------------|---------|--------|--------------------------------------------------------|-------------------------------------|
| `relay.retrying.fixed-delay-ms` | *(미설정)* | `5000` | `RetryingNotificationJobExecutionScheduler` @Scheduled | RETRYING → PROCESSING 전이 폴링 주기 (ms) |
| `relay.retrying.batch-size`     | *(미설정)* | `20`   | `RetryingNotificationJobExecutionScheduler` 생성자        | 한 번에 가져오는 RETRYING Job 수            |

> `relay.execution.*`과 `relay.retrying.*`은 application.yml에 명시되어 있지 않으며, `NotificationProperties` record의 compact
> constructor 기본값으로 동작합니다.

### notification.handler — 이벤트 핸들러 멱등성 TTL

분산 락의 TTL로 사용됩니다. 이벤트 중복 처리를 방지하는 핵심 설정입니다.

| 키                                   | 현재 값  | 기본값   | 사용처                                                                                                                                                                                                                                               | 설명                  |
|-------------------------------------|-------|-------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------|
| `handler.created-idempotency-ttl`   | `5m`  | `10m` | `NotificationJobCreatedProcessor`, `CreateScheduledNotificationJobUseCase`                                                                                                                                                                        | Job 생성 이벤트 분산 락 TTL |
| `handler.execution-idempotency-ttl` | `10m` | `30m` | `NotificationJobExecutionProcessor` (+ Watchdog), `DbScheduledNotificationJobRelay`, `DbRetryScheduledNotificationRecoverRelay`, `RetryingNotificationJobExecutionScheduler`, `StuckProcessingRecoveryScheduler`, `RecoverNotificationJobUseCase` | 실행/릴레이/복구 분산 락 TTL  |

> `execution-idempotency-ttl`은 6개 컴포넌트에서 공유합니다. 값 변경 시 영향 범위에 주의가 필요합니다.

### notification.retry — 재시도 정책

`DefaultSendFailureClassifier`와 `RetryBackoffCalculator`가 사용합니다.

| 키                          | 현재 값    | 기본값   | 사용처                                                                | 설명                                        |
|----------------------------|---------|-------|--------------------------------------------------------------------|-------------------------------------------|
| `retry.max-send-try-count` | `3`     | `5`   | `DefaultSendFailureClassifier`, `StuckProcessingRecoveryScheduler` | 최대 발송 시도 횟수. 초과 시 DEAD_LETTER             |
| `retry.base-delay`         | `5s`    | `30s` | `RetryBackoffCalculator`                                           | 지수 백오프 기본 지연                              |
| `retry.max-delay`          | `30s`   | `1h`  | `RetryBackoffCalculator`                                           | 지수 백오프 최대 지연 상한                           |
| `retry.jitter-ratio`       | *(미설정)* | `0.0` | `RetryBackoffCalculator`                                           | 지연에 적용할 jitter 비율 (0.0 = 미적용, 0.0~1.0 범위) |

> 백오프 공식: `nextRetryAt = now + clamp(baseDelay × 2^(sendTryCount - 1) ± jitter, 1s, maxDelay)`

### notification.batch

| 키            | 현재 값  | 기본값   | 사용처                            | 설명       |
|--------------|-------|-------|--------------------------------|----------|
| `batch.size` | `200` | `200` | `NotificationProperties.Batch` | 일괄 처리 크기 |

### notification.metrics — 파이프라인 메트릭 폴링

`NotificationPipelineMetricsScheduler`가 DB를 폴링하여 Prometheus 메트릭을 갱신하는 주기입니다.

| 키                          | 현재 값   | 기본값     | 사용처                                               | 설명                                       |
|----------------------------|--------|---------|---------------------------------------------------|------------------------------------------|
| `metrics.poll-interval-ms` | `5000` | `10000` | `NotificationPipelineMetricsScheduler` @Scheduled | 메트릭 폴링 주기 (ms). 로컬에서 빠른 확인이 필요하면 줄일 수 있음 |

### notification.stuck-recovery — Stuck PROCESSING 복구

`StuckProcessingRecoveryScheduler`가 장시간 PROCESSING 상태에 머무는 Job을 감지하고 복구합니다.

| 키                                      | 현재 값    | 기본값     | 사용처                                           | 설명                                     |
|----------------------------------------|---------|---------|-----------------------------------------------|----------------------------------------|
| `stuck-recovery.fixed-delay-ms`        | `10000` | `60000` | `StuckProcessingRecoveryScheduler` @Scheduled | Stuck 감지 폴링 주기 (ms)                    |
| `stuck-recovery.batch-size`            | `50`    | `50`    | `StuckProcessingRecoveryScheduler` 생성자        | 한 번에 감지하는 Stuck Job 수                  |
| `stuck-recovery.stuck-timeout-seconds` | `30`    | `300`   | `StuckProcessingRecoveryScheduler`            | PROCESSING 상태 유지 임계 시간 (초). 초과 시 복구 대상 |

---

## mock.email.* — Mock 이메일 발송 시뮬레이션

`MockEmailSenderControl`로 바인딩됩니다.  
`!prod` 프로파일에서만 활성화되며, Actuator endpoint(`/actuator/mock-email`)로 런타임 변경 가능합니다.

| 키                            | 현재 값        | 기본값         | 설명                                |
|------------------------------|-------------|-------------|-----------------------------------|
| `mock.email.fail-rate`       | `0.3`       | `0.05`      | 발송 실패 확률 (0.0~1.0)                |
| `mock.email.fail-type`       | `TRANSIENT` | `TRANSIENT` | 실패 유형 (`TRANSIENT` / `PERMANENT`) |
| `mock.email.latency-ms`      | `50`        | `0`         | 발송 지연 (ms). 0이면 10~100ms 랜덤 적용    |
| `mock.email.hang-rate`       | `0.0`       | `0.0`       | hang 발생 확률 (0.0~1.0)              |
| `mock.email.hang-timeout-ms` | `30000`     | `30000`     | hang 발생 시 대기 시간 (ms)              |

---

## Spring 프레임워크 설정 (커스터마이징 항목)

기본값에서 변경하거나 명시적으로 지정한 Spring 설정입니다.

### 애플리케이션 런타임

| 키                                             | 현재 값   | 설명                                              |
|-----------------------------------------------|--------|-------------------------------------------------|
| `spring.threads.virtual.enabled`              | `true` | Virtual Thread 활성화 (Tomcat, @Async, @Scheduled) |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s`  | Graceful shutdown 대기 시간                         |

### JPA / Hibernate

| 키                                                 | 현재 값     | 설명                   |
|---------------------------------------------------|----------|----------------------|
| `spring.jpa.hibernate.ddl-auto`                   | `update` | 스키마 자동 갱신            |
| `spring.jpa.properties.hibernate.jdbc.batch_size` | `500`    | INSERT 배치 크기         |
| `spring.jpa.properties.hibernate.order_inserts`   | `true`   | 배치 INSERT를 위한 엔티티 정렬 |
| `spring.jpa.open-in-view`                         | `false`  | OSIV 비활성화            |

### DataSource / HikariCP

| 키                                             | 현재 값   | 설명               |
|-----------------------------------------------|--------|------------------|
| `spring.datasource.hikari.maximum-pool-size`  | `20`   | 커넥션 풀 최대 크기      |
| `spring.datasource.hikari.minimum-idle`       | `5`    | 최소 유휴 커넥션        |
| `spring.datasource.hikari.connection-timeout` | `5000` | 커넥션 획득 타임아웃 (ms) |

### Spring Modulith

| 키                                        | 현재 값      | 설명                                                                      |
|------------------------------------------|-----------|-------------------------------------------------------------------------|
| `spring.modulith.events.completion-mode` | `archive` | 완료 이벤트를 삭제하지 않고 아카이빙. `EventPublicationCleanupScheduler`가 7일 경과 레코드를 정리 |

---

## 참고: 현재 값과 기본값 차이가 큰 항목

테스트 편의를 위해 기본값보다 짧게 설정된 항목입니다. 운영 환경 적용 시 조정이 필요합니다.

| 키                                      | 현재 값 | 기본값  | 배율      |
|----------------------------------------|------|------|---------|
| `stuck-recovery.fixed-delay-ms`        | 10초  | 60초  | 6배 짧음   |
| `stuck-recovery.stuck-timeout-seconds` | 30초  | 300초 | 10배 짧음  |
| `retry.base-delay`                     | 5초   | 30초  | 6배 짧음   |
| `retry.max-delay`                      | 30초  | 1시간  | 120배 짧음 |
| `retry.max-send-try-count`             | 3회   | 5회   | —       |
| `handler.created-idempotency-ttl`      | 5분   | 10분  | 2배 짧음   |
| `handler.execution-idempotency-ttl`    | 10분  | 30분  | 3배 짧음   |
| `metrics.poll-interval-ms`             | 5초   | 10초  | 2배 짧음   |
| `mock.email.fail-rate`                 | 0.3  | 0.05 | 6배 높음   |
