# 5. 운영 시나리오 대응

> 다중 인스턴스 중복 처리 방지는 [3. 중복 발송 방지](03-duplicate-prevention.md)에서 다룹니다.

## 설계 의도

이 프로젝트는 happy path만 구현하지 않고, 운영 중 실제로 발생할 수 있는 예외 상황을 별도 시나리오로 분리해 다루도록 설계했습니다.

각 운영 시나리오는 대응 주체(UseCase / Scheduler / Reconciler)가 명확히 분리되어 있고, 상태 전이는 반드시 `JobNotificationPolicy`를 통해서만 이루어집니다.

---

## 재시도

### 실패 분류

발송 실패는 모두 동일하게 처리하지 않습니다. 외부 채널에서 수신한 실패 응답을 `FailureClassification`으로 분류합니다.

| 분류          | 의미                             | 처리             |
|-------------|--------------------------------|----------------|
| `TRANSIENT` | 일시적 장애 (네트워크 오류, 일시적 서버 과부하 등) | 재시도 대상         |
| `PERMANENT` | 영구 장애 (잘못된 수신자 주소, 권한 오류 등)    | dead letter 대상 |

재시도 가능한 경우라도 `sendTryCount >= maxSendTryCount`를 초과하면 `DEAD_LETTER`로 전이합니다.

### 재시도 흐름

**[Step 1] 발송 실패 처리 — `NotificationJobSendOrchestrator`**

```
발송 실패 (TRANSIENT, sendTryCount < max)
  ├─ Notification: SENDING → FAILED → RETRY_WAITING
  └─ NotificationJob: PROCESSING → FAILED
       └─ NotificationJobSchedulePort.retrySchedule()   재시도 스케줄 등록
```

**[Step 2] 재시도 스케줄 도달 — `DbRetryScheduledNotificationRecoverRelay`**

```
재시도 스케줄 도달 (ScheduledNotificationJob.type = RETRY)
  └─ distributedLock.tryLock(idempotencyKey)
       └─ SELECT FOR UPDATE → NotificationJob: FAILED → RETRYING
       └─ NotificationJobStatusChangedEvent 발행
```

**[Step 3] 재시도 실행 — `RetryingNotificationJobExecutionScheduler`**

```
RetryingNotificationJobExecutionScheduler (주기적 폴링)
  └─ distributedLock.tryLock(idempotencyKey)
       └─ SELECT FOR UPDATE → NotificationJob: RETRYING → PROCESSING
       └─ NotificationJobExecutionEvent 발행
            └─ NotificationJobExecutionProcessor
                 └─ Notification: RETRY_WAITING → SENDING → SENT / FAILED
```

---

## 취소

취소는 모든 상태에서 허용하지 않습니다.

| NotificationJob 상태                 | 취소 가능 여부 | 이유            |
|------------------------------------|----------|---------------|
| `CREATED`, `SCHEDULED`, `RETRYING` | 가능       | 발송 실행 경계 진입 전 |
| `PROCESSING`                       | 불가       | 발송 중          |
| `COMPLETED`, `FAILED`              | 불가       | 이미 종료         |
| `CANCELLED`                        | 멱등 처리    | 이미 취소됨        |

개별 Notification도 `SENDING`은 취소 대상에서 제외했습니다. 이미 외부 채널 호출이 나간 상태이기 때문에, 중간에 취소를 허용하면 결과 반영 흐름과 충돌할 수 있습니다.

**`CancelNotificationJobUseCase`**

```
CancelNotificationJobUseCase.execute(jobId)
  ├─ 1. NotificationJob 조회 → distributedLock.tryLock(idempotencyKey)
  │     └─ 락 획득 실패: skip (동시 요청 차단)
  ├─ 2. SELECT FOR UPDATE → NotificationJob: → CANCELLED
  │     └─ 취소 불가 상태(PROCESSING / COMPLETED / FAILED): IllegalStateException
  ├─ 3. NotificationJobStatusChangedEvent 발행
  ├─ 4. Notification: PENDING / RETRY_WAITING / FAILED / DEAD_LETTER → CANCELLED (일괄)
  │     └─ SENDING 상태 Notification은 제외 (이미 외부 채널 호출 진행 중)
  └─ 5. schedulePort.cancelSchedule(jobId)   (finally: lock 해제)
```

---

## 수동 복구

실패 또는 취소된 NotificationJob은 운영자가 다시 살릴 수 있어야 한다고 판단해 manual recovery 흐름을 두었습니다. `FAILED`와 `CANCELLED` 두 상태 모두 `RETRYING`
으로 전환할 수 있습니다.

**`RecoverNotificationJobUseCase`**

```
RecoverNotificationJobUseCase.execute(jobId)
  ├─ 1. distributedLock.tryLock(idempotencyKey)
  │     └─ 락 획득 실패: skip
  ├─ 2. SELECT FOR UPDATE → NotificationJob 상태 재검증
  ├─ 3. job.markRecovering()
  │     └─ NotificationJob: FAILED / CANCELLED → RETRYING
  ├─ 4. 출발 상태에 따라 Notification 리셋 (같은 트랜잭션)
  │     FAILED 출발: Notification: DEAD_LETTER → RETRY_WAITING
  │     CANCELLED 출발: Notification: CANCELLED → RETRY_WAITING
  │     └─ sendTryCount 리셋
  ├─ 5. publish(NotificationJobStatusChangedEvent)
  └─ 6. distributedLock.unlock()

이후 RetryingNotificationJobExecutionScheduler (주기적 폴링)
  └─ NotificationJob: RETRYING → PROCESSING + NotificationJobExecutionEvent 발행
```

수동 복구를 "운영자의 별도 판단이 들어간 새로운 재시도 기회"로 보기 때문에 `sendTryCount`를 리셋했습니다. 이전 실패가 외부 장애였다면 재시도 한도를 이미 소진했더라도 다시 시도할 수 있어야 합니다.

---

## Stuck Processing 복구

`NotificationJob`은 `PROCESSING`, `Notification`은 `SENDING` 상태인데 프로세스만 죽어버리면, 단순 retry/cancel 로직만으로는 복구가 어렵습니다.

이를 위해 두 가지 장치를 두었습니다.

### NotificationJobRestartReconciler — 재시작 복구

애플리케이션 기동 시 `ApplicationReadyEvent`를 수신하여 실행됩니다. 현재 구현에는 재시작 직후 중간 상태를 보정하기 위한 `NotificationJobRestartReconciler`가 존재하며,
이전 실행 흐름이 남긴 `SENDING` 상태를 재처리 가능한 상태로 되돌리는 역할을 담당합니다.

```
ApplicationReadyEvent 수신
  └─ PROCESSING 상태 NotificationJob 조회
       └─ Notification: SENDING → RETRY_WAITING (무조건 롤백)
            └─ NotificationJob 상태는 PROCESSING 유지
```

**NotificationJob 상태를 변경하지 않는 이유**: `PROCESSING`은 "현재 처리 가능한 상태"를 의미합니다. 잔류 `SENDING`을 `RETRY_WAITING`으로 롤백하면 처리할 알림이 다시
생긴 것이므로, NotificationJob은 여전히 `PROCESSING`으로 두는 것이 맞습니다. `PROCESSING` 상태인 NotificationJob은
`ProcessingNotificationJobExecutionScheduler`가 주기적으로 감지해 실행 이벤트를 발행하므로 별도 트리거 없이 정상 재처리 흐름으로 이어집니다.

### StuckProcessingRecoveryScheduler — 런타임 stuck 감지

재시작 없이도 장시간 `PROCESSING`에 머문 NotificationJob을 `@Scheduled`로 주기적으로 감지합니다. 핵심 목적은 런타임 중 중간 상태에 머무는 NotificationJob과
Notification을 방치하지
않고, 다시 처리 가능하거나 최종 실패로 정리 가능한 상태로 이동시키는 것입니다.

```
PROCESSING 상태 NotificationJob 감지 (updatedAt < cutoff)
  └─ 1. distributedLock.tryLock(idempotencyKey)   다중 인스턴스 중복 복구 방지
  └─ 2. SENDING 상태 Notification 처리
       ├─ sendTryCount < max → Notification: SENDING → RETRY_WAITING
       └─ sendTryCount >= max → Notification: SENDING → DEAD_LETTER
            └─ NotificationSendHistory 기록 (FailureReasonCode.STUCK_TIMEOUT)
  └─ 3. 활성 Notification(PENDING / RETRY_WAITING)이 남아 있으면
       └─ NotificationJob 상태 PROCESSING 유지
  └─ 4. 활성 Notification이 하나도 없으면
       └─ NotificationJob: PROCESSING → FAILED

이후 운영자는 수동 복구로 `FAILED → RETRYING` 전환 가능
```

### 두 장치의 보완 관계

|       | `NotificationJobRestartReconciler`  | `StuckProcessingRecoveryScheduler` |
|-------|-------------------------------------|------------------------------------|
| 트리거   | 앱 기동 시 1회 (`ApplicationReadyEvent`) | 주기적 폴링 (`@Scheduled`)              |
| 대상 상황 | 서버 재시작·크래시 직후                       | 재시작 없이 런타임 중 중간 상태 장기 체류           |
| 핵심 역할 | 재시작 직후 중간 상태 보정                     | 런타임 중 stuck 상태 감지 및 정리             |

---

## 테스트 기반 검증

운영 시나리오 대응은 문서로만 설명하지 않고, `scripts/test-with-server.sh`에서 실제로 검증 시나리오를 포함했습니다.

- happy path
- transient retry 후 성공
- permanent failure 후 dead letter
- 취소
- max retry 초과
- manual recovery
- stuck recovery

운영 시나리오는 설계 문구가 아니라 **실제로 재현 가능한 테스트 시나리오**와 함께 구현했습니다.
