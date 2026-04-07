# 상태 머신 설계

`NotificationJob`과 `Notification`은 각각 독립적인 상태 머신으로 동작하며,
`JobNotificationPolicy`가 두 머신의 상태 전이를 결합하는 정책 레이어 역할을 합니다.

---

## 1. 두 개의 상태 머신

### JobStatus — Job 단위 상태 머신

`NotificationJob` 하나가 관리하는 발송 작업 전체의 진행 상태입니다.

```
CREATED ──► SCHEDULED ──► PROCESSING ──► COMPLETED
   │             │              │
   └──► CANCELLED ◄────┐        └──► FAILED
              │         │                │
              └──► RETRYING ─────────────┘
                        │
                        └──► CANCELLED
```

| 상태           | 유형       | 설명                            |
|--------------|----------|-------------------------------|
| `CREATED`    | 초기       | Job이 생성된 직후. 발송 시각 미확정        |
| `SCHEDULED`  | 진행       | 발송 시각이 확정되어 스케줄러에 등록됨         |
| `PROCESSING` | 진행       | 실제 알림 발송이 진행 중                |
| `COMPLETED`  | terminal | 모든 알림 발송이 완료                  |
| `FAILED`     | 오류       | 처리 실패. `RETRYING`으로 재도전 가능    |
| `CANCELLED`  | 취소       | Job이 취소됨. `RETRYING`으로 재도전 가능 |
| `RETRYING`   | 진행       | 실패·취소 후 재처리 준비 중              |

허용 전이는 `JobStatus` enum의 static 블록에 선언됩니다 (`JobStatus.java:14-22`).

---

### NotificationStatus — 개별 알림 상태 머신

`Notification` 하나(수신자 1명)의 발송 상태입니다.

```
PENDING ──► SENDING ──► SENT (terminal)
   │            │
   │            ├──► FAILED ──► RETRY_WAITING ──► SENDING (재시도)
   │            │         │
   │            │         └──► DEAD_LETTER ──► RETRY_WAITING (수동복구)
   │            │                    │
   │            └──► DEAD_LETTER     └──► CANCELLED
   │
   └──► CANCELLED ──► RETRY_WAITING
```

| 상태              | 유형       | 설명                                |
|-----------------|----------|-----------------------------------|
| `PENDING`       | 초기       | 생성되어 발송 대기 중                      |
| `SENDING`       | 진행       | 외부 채널 발송 요청 진행 중                  |
| `SENT`          | terminal | 발송 성공                             |
| `FAILED`        | 오류       | 발송 실패. 재시도 또는 격리 대상               |
| `RETRY_WAITING` | 대기       | 재시도 인터벌 대기 중                      |
| `DEAD_LETTER`   | 격리       | 재시도 한도 초과 또는 복구 불가 상태. 수동 개입 필요   |
| `CANCELLED`     | 취소       | 취소됨. 수동 복구로 `RETRY_WAITING` 전이 가능 |

허용 전이는 `NotificationStatus` enum의 static 블록에 선언됩니다 (`NotificationStatus.java:14-22`).

---

## 2. JobNotificationPolicy — 두 머신을 묶는 정책

`JobNotificationPolicy`는 "Job 상태가 X → Y로 전이될 때, Notification에는 어떤 전이가 허용되는가"를 하나의 enum 상수로 선언합니다.

```java
// 예: TO_PROCESSING 정책
TO_PROCESSING(
    fromStatuses = { SCHEDULED, RETRYING },
    toStatus     = PROCESSING,
    notificationTransitions = {
        PENDING       → SENDING,
        RETRY_WAITING → SENDING,
        SENDING       → SENT,
        SENDING       → FAILED,
        FAILED        → RETRY_WAITING,
        FAILED        → DEAD_LETTER
    }
)
```

정책이 하는 역할은 두 가지입니다.

1. **Job 전이 가드** — `fromStatuses`에 현재 Job 상태가 없으면 전이를 거부합니다.
2. **Notification 전이 허용 목록** — 해당 정책 범위 안에서 이 목록에 없는 Notification 전이는 `IllegalStateException`으로 차단됩니다.

### 전체 정책 목록

| 정책                       | Job 전이                                         | Notification 전이                                                                                                            | 비고                                                            |
|--------------------------|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| `TO_SCHEDULED`           | `CREATED` → `SCHEDULED`                        | (없음)                                                                                                                       | 스케줄 확정                                                        |
| `TO_PROCESSING`          | `SCHEDULED`/`RETRYING` → `PROCESSING`          | `PENDING→SENDING`, `RETRY_WAITING→SENDING`, `SENDING→SENT`, `SENDING→FAILED`, `FAILED→RETRY_WAITING`, `FAILED→DEAD_LETTER` | 발송 체인 전체 커버                                                   |
| `TO_CANCELLED`           | `CREATED`/`SCHEDULED`/`RETRYING` → `CANCELLED` | `PENDING→CANCELLED`, `RETRY_WAITING→CANCELLED`, `FAILED→CANCELLED`, `DEAD_LETTER→CANCELLED`                                | `SENDING` 중 취소 불가                                             |
| `TO_RETRYING`            | `FAILED`/`CANCELLED` → `RETRYING`              | `DEAD_LETTER→RETRY_WAITING`, `CANCELLED→RETRY_WAITING`                                                                     | 수동 복구 진입                                                      |
| `TO_COMPLETED`           | `PROCESSING` → `COMPLETED`                     | (없음)                                                                                                                       | 모두 terminal 확인 후 호출                                           |
| `TO_FAILED`              | `PROCESSING` → `FAILED`                        | (없음)                                                                                                                       | 재시도 대상 잔존 시 호출                                                |
| `STUCK_RECOVERY`         | `PROCESSING` → `FAILED`                        | `SENDING→DEAD_LETTER`                                                                                                      | `StuckProcessingRecoveryScheduler` 전용                         |
| `STUCK_SENDING_ROLLBACK` | `PROCESSING` → `PROCESSING` (self)             | `SENDING→RETRY_WAITING`                                                                                                    | `NotificationJobRestartReconciler` 전용. 서버 재시작 후 잔류 SENDING 롤백 |

---

## 3. NotificationJob — 애그리게이트이자 전이 게이트

`NotificationJob`은 두 상태 머신의 진입점을 모두 소유합니다.
외부에서는 반드시 `NotificationJob`의 메서드를 통해서만 상태 전이를 수행하며,
`Notification`의 상태 전이 메서드(`markSending()` 등)는 package-private으로 직접 호출을 차단합니다.

### Job 자신의 상태 전이

```java
job.markScheduled();    // TO_SCHEDULED 정책 적용
job.markProcessing();   // TO_PROCESSING 정책 적용
job.markCompleted();    // TO_COMPLETED 정책 적용
job.markFailed();       // TO_FAILED 정책 적용
job.markCancelled();    // TO_CANCELLED 정책 적용
job.markRetrying();     // TO_RETRYING 정책 적용
job.markStuckRecovery();// STUCK_RECOVERY 정책 적용
```

내부 구현은 `transitionTo(policy)`를 통해 `policy.getFromStatuses()`에 현재 상태가 포함되는지 검사하고, 포함되면 `policy.getToStatus()`로 전이합니다.

### Notification 상태 전이 위임

`NotificationJob`이 Notification의 전이를 대리할 때는 반드시 정책의 허용 목록을 먼저 검증합니다.

```java
// assertNotificationTransitionAllowed()가 정책 허용 목록 검사
job.startSendingNotification(n);          // TO_PROCESSING: PENDING/RETRY_WAITING → SENDING
job.completeSendingNotification(n);       // TO_PROCESSING: SENDING → SENT
job.failSendingNotification(n, ...);      // TO_PROCESSING: SENDING → FAILED
job.failStuckSendingNotification(n, ...); // STUCK_RECOVERY: SENDING → DEAD_LETTER
job.rollbackStuckSendingToRetryWaiting(n);// STUCK_SENDING_ROLLBACK: SENDING → RETRY_WAITING
job.cancelNotification(n);               // TO_CANCELLED: → CANCELLED
job.resetNotificationForManualRetry(n);  // TO_RETRYING: → RETRY_WAITING (sendTryCount 리셋)
```

정책에 없는 전이가 시도되면 `assertNotificationTransitionAllowed()`가 `IllegalStateException`을 던집니다.

---

## 4. 설계 의도

### 정책이 허용 목록을 선언하는 이유

`TO_PROCESSING` 정책은 `PENDING→SENDING`뿐 아니라 `SENDING→SENT`, `SENDING→FAILED` 등 발송 체인 전체를 허용 목록으로 선언합니다.
이는 "Job이 PROCESSING 상태일 때 일어날 수 있는 모든 Notification 전이"를 한 곳에서 읽을 수 있게 하기 위함입니다.
코드를 읽는 사람이 정책 enum 하나만 보면 해당 Job 상태 구간에서 Notification에 무슨 일이 벌어질 수 있는지 파악할 수 있습니다.

### Notification 전이 메서드를 package-private으로 제한하는 이유

`Notification.markSending()` 등의 메서드를 package-private으로 두면, 도메인 패키지 외부에서 정책 검증을 우회하는 직접 호출이 불가능해집니다.
상태 전이는 반드시 `NotificationJob`을 통해서만 가능하며, 이 과정에서 정책 허용 목록 검사가 강제됩니다.

### self-transition 정책 (STUCK_SENDING_ROLLBACK)

`STUCK_SENDING_ROLLBACK`은 Job 상태를 `PROCESSING → PROCESSING`으로 유지한 채 Notification만 롤백하는 예외적 정책입니다.
서버 재시작 후 `NotificationJobRestartReconciler`가 잔류 `SENDING` Notification을 `RETRY_WAITING`으로 되돌려 다음 실행 사이클에서 정상 재처리되도록 합니다.
Job 상태가 변경되지 않으므로 Job 상태 다이어그램에는 등장하지 않습니다.
