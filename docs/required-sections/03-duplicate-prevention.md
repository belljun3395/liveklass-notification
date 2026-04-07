# 3. 중복 발송 방지

## 설계 의도

중복 발송은 단일 지점에서 막을 수 없습니다. 네트워크 재시도로 같은 생성 요청이 두 번 들어올 수 있고, 비동기 이벤트가 중복 발행될 수도 있습니다. 또한 현재 구현은 단일 인스턴스 환경 기준이지만, 향후 다중
인스턴스 환경에서도 같은 알림 잡을 동시에 처리하려는 상황까지 고려할 수 있도록 인터페이스를 분리했습니다.

그래서 단일 장치가 아니라 **파이프라인 각 단계마다 방어선을 두는 방식**으로 설계했습니다.

---

## 생성 단계

**`IssueCreationJobKeyUseCase` — `CreateScheduledNotificationJobUseCase`**

```
[1차] 서버 발급 멱등성 키 검증
IssueCreationJobKeyUseCase.execute()
  └─ idempotencyStore.generateKey()   → 고유 키 생성 + 발급 이력 등록
CreateScheduledNotificationJobUseCase.execute(idempotencyKey, ...)
  └─ idempotencyStore.isIssued(key)
       └─ 미발급 키: IllegalArgumentException   클라이언트 임의 키 차단

[2차] 동시 중복 생성 차단
  └─ distributedLock.tryLock(key, ttl=10m)
       └─ 락 획득 실패: DuplicateResourceException   동시 요청 차단

[3차] 순차 중복 요청 멱등 응답
  └─ DB에서 동일 키 NotificationJob 조회
       └─ 이미 존재: 기존 NotificationJob 반환 (created=false)   네트워크 재시도 대응
```

| 방어 지점       | 메커니즘                                    | 차단 대상                   |
|-------------|-----------------------------------------|-------------------------|
| 키 발급 검증     | `idempotencyStore.isIssued(key)`        | 클라이언트 임의 키 사용, 미발급 키 요청 |
| 동시 생성 차단    | `distributedLock.tryLock(key, ttl=10m)` | 동시 중복 요청                |
| 순차 중복 요청 대응 | DB에서 동일 키 조회 후 기존 NotificationJob 반환    | 네트워크 재시도, 클라이언트 중복 호출   |

---

## 이벤트 처리 단계

중복은 생성 단계에서만 생기지 않습니다. 이벤트 자체가 중복 발행되거나 재전달될 수도 있습니다. 그래서 각 프로세서 진입 시 분산 락을 다시 획득합니다.

| 프로세서                                | 처리하는 이벤트                        | 락 기준             |
|-------------------------------------|---------------------------------|------------------|
| `NotificationJobCreatedProcessor`   | `NotificationJobCreatedEvent`   | `idempotencyKey` |
| `NotificationJobExecutionProcessor` | `NotificationJobExecutionEvent` | `idempotencyKey` |

```java
// 두 프로세서 공통 패턴
Optional<String> lockToken = distributedLock.tryLock(event.idempotencyKey(), idempotencyTtl);
if (lockToken.isEmpty()) {
    log.info("[Handler] Duplicate event for job {}, skipping", event.jobId());
    return;
}
```

at-least-once 전달을 감안한 설계입니다. 이벤트가 여러 번 와도 실제 효과는 한 번만 나도록 보장합니다.

| 방어 지점        | 메커니즘                           | 차단 대상           |
|--------------|--------------------------------|-----------------|
| Processor 중복 | `distributedLock.tryLock(key)` | 이벤트 중복 수신 및 재전달 |

---

## 스케줄러 실행 단계

`ProcessingNotificationJobExecutionScheduler`는 `PROCESSING` 상태의 NotificationJob을 선별해 실행 이벤트를 발행하고, 중복 실행 방지는 실제 처리 단계인
`NotificationJobExecutionProcessor`에 위임합니다.

`RetryingNotificationJobExecutionScheduler`와 `StuckProcessingRecoveryScheduler`는 두 단계로 중복을 막습니다.

**배치 조회 시 SKIP LOCKED** — 같은 스케줄러 타입의 다중 인스턴스가 동일 Row를 동시에 읽는 것을 방지합니다. 단, SKIP LOCKED는 같은 쿼리 간에만 효과가 있어, 서로 다른 스케줄러 타입 간 경합(예: `RetryingScheduler`와 `StuckProcessingRecoveryScheduler`가 상태·시간 조건 겹침)은 차단하지 못합니다.

**개별 처리 시 분산 락 + 재조회 + 상태 재검증** — 배치 조회 시점과 실제 상태 전이 시점 사이의 간격(TOCTOU)을 메웁니다. 락을 획득한 뒤 `SELECT FOR UPDATE`로 재조회해 상태를 다시 확인하므로, 서로 다른 스케줄러 타입이 같은 NotificationJob을 배치로 가져갔더라도 실제 전이는 한 번만 일어납니다.

```java
// RetryingNotificationJobExecutionScheduler / StuckProcessingRecoveryScheduler 공통 패턴
// 1. 대상 Job 배치 조회
List<NotificationJob> jobs = jobRepository.findByStatusAndDeletedFalse(status, pageable);

// 2. 개별 처리 시 분산 락 획득
Optional<String> lockToken = distributedLock.tryLock(idempotencyKey, ttl);
if(lockToken.isEmpty()){return;}  // 다른 실행 흐름이 처리 중

// 3. 실제 상태 전이 전 재조회 + 상태 재검증
NotificationJob job = jobRepository.findByIdAndDeletedFalseForUpdate(jobId)
        .orElse(null);
```

| 컴포넌트                                          | 배치 조회               | 개별 처리                              | 차단 대상            |
|-----------------------------------------------|---------------------|------------------------------------|------------------|
| `ProcessingNotificationJobExecutionScheduler` | `PROCESSING` Job 선별 | 없음 (Processor에 위임)                 | 배치 중복 선별/실행      |
| `RetryingNotificationJobExecutionScheduler`   | `RETRYING` Job 선별   | 분산 락 + `NotificationJob` 재조회/상태 검증 | 중복 상태 전이         |
| `StuckProcessingRecoveryScheduler`            | stuck 대상 Job 선별     | 분산 락 + `NotificationJob` 재조회/상태 검증 | 동일 stuck 잡 중복 복구 |

---

## 정리

| 단계         | 방어 지점         | 메커니즘                                       | 차단 대상                        |
|------------|---------------|--------------------------------------------|------------------------------|
| 생성         | 키 발급 검증       | `idempotencyStore.isIssued(key)`           | 임의 키 사용, 미발급 키 요청            |
| 생성         | 동시 생성 차단      | `distributedLock.tryLock`                  | 동시 중복 요청                     |
| 생성         | 순차 중복 요청 대응   | DB 조회 후 기존 NotificationJob 반환              | 네트워크 재시도, 클라이언트 중복 호출        |
| 이벤트 처리     | Processor 중복  | 각 프로세서 진입 시 `tryLock`                      | 이벤트 중복 발행, at-least-once 재전달 |
| 스케줄러 실행    | 배치 조회 경합      | `NotificationJob` SKIP LOCKED              | 인스턴스 간 동시 폴링                 |
| 스케줄러 실행    | 개별 처리 중복      | 분산 락 + `NotificationJob` SELECT FOR UPDATE | 동일 잡 중복 상태 전이                |
