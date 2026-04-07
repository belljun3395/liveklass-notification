# 2. 알림 처리 상태 관리

## 알림 상태 정의

### 상태 모델

알림 발송과 관련된 상태는 크게 두 가지 축으로 나눌 수 있습니다.

#### NotificationJob 상태

- `CREATED`
- `SCHEDULED`
- `PROCESSING`
- `FAILED`
- `COMPLETED`
- `CANCELLED`
- `RETRYING`

알림 발송 작업 전체의 상태를 나타냅니다. 예를 들어, NotificationJob이 생성되었는지, 발송 중인지, 실패했는지 등을 나타냅니다.

#### Notification 상태

- `PENDING`
- `SENDING`
- `SENT`
- `FAILED`
- `RETRY_WAITING`
- `DEAD_LETTER`
- `READ`
- `CANCELLED`

개별 알림의 상태를 나타냅니다. 예를 들어, 특정 사용자에게 보낼 알림이 아직 대기 중인지, 발송 중인지, 성공적으로 발송되었는지 등을 나타냅니다.

### 상태 전이 설계

#### JobNotificationPolicy

NotificationJob과 Notification의 상태 전이 규칙을 정의하는 `JobNotificationPolicy`를 설계했습니다.

이 정책은 각 상태에서 허용되는 전이와 금지되는 전이를 명확히 규정합니다.

| 정책                       | Job 전이 (from → to)                                 | Notification 전이 허용 목록                                                                                                                                        | 설명                                                               |
|--------------------------|----------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| `TO_SCHEDULED`           | `CREATED` → `SCHEDULED`                            | (없음)                                                                                                                                                         | 발송 스케줄 등록 완료. Notification 상태 변경 없음                              |
| `TO_PROCESSING`          | `SCHEDULED` / `RETRYING` → `PROCESSING`            | `PENDING` → `SENDING`<br>`RETRY_WAITING` → `SENDING`<br>`SENDING` → `SENT`<br>`SENDING` → `FAILED`<br>`FAILED` → `RETRY_WAITING`<br>`FAILED` → `DEAD_LETTER` | 실제 발송이 시작되는 전이. Notification 발송 체인 전체를 커버                        |
| `TO_CANCELLED`           | `CREATED` / `SCHEDULED` / `RETRYING` → `CANCELLED` | `PENDING` → `CANCELLED`<br>`RETRY_WAITING` → `CANCELLED`<br>`FAILED` → `CANCELLED`<br>`DEAD_LETTER` → `CANCELLED`                                            | 발송 실행 경계 진입 전 상태에서만 취소 허용. `SENDING` 제외                          |
| `TO_RETRYING`            | `FAILED` / `CANCELLED` → `RETRYING`                | `DEAD_LETTER` → `RETRY_WAITING`<br>`CANCELLED` → `RETRY_WAITING`                                                                                             | 운영자 수동 복구. `sendTryCount` 리셋 포함                                  |
| `TO_COMPLETED`           | `PROCESSING` → `COMPLETED`                         | (없음)                                                                                                                                                         | 모든 Notification이 terminal 상태일 때                                  |
| `TO_FAILED`              | `PROCESSING` → `FAILED`                            | (없음)                                                                                                                                                         | 발송 완료 후 재시도 대상 알림이 존재하거나 전체 실패 시                                 |
| `STUCK_RECOVERY`         | `PROCESSING` → `FAILED`                            | `SENDING` → `DEAD_LETTER`                                                                                                                                    | `StuckProcessingRecoveryScheduler` 전용. 재시도 한도 초과 잔류 `SENDING` 처리 |
| `STUCK_SENDING_ROLLBACK` | `PROCESSING` → `PROCESSING`                        | `SENDING` → `RETRY_WAITING`                                                                                                                                  | `NotificationJobRestartReconciler` 전용. 서버 재시작 시 잔류 `SENDING` 롤백  |

cc. [job-state-transition 다이어그램](../generated-docs/state/job-state-transition.adoc), [notification-state-transition 다이어그램](../generated-docs/state/notification-state-transition.adoc)

## 실패 처리와 재시도

### 실패 판단

알림 발송의 경우 일시적 실패(`TRANSIENT`)와 영구 실패(`PERMANENT`)로 분류한다.
일시적 실패의 경우 재시도 대상이지만, 영구 실패는 재시도해도 소용이 없기 때문에 dead letter 대상이 된다.
이때 일시적 실패의 경우 재시도 횟수도 함께 기록해서, 최대 재시도 횟수를 넘는 경우에도 dead letter로 전이한다.

알림 잡의 경우 개별 알림 발송 결과를 종합적으로 판단해 알림 잡 전체의 성공(`COMPLETED`)/실패(`FAILED`) 여부를 결정한다.

### 재시도 정책

개별 알림이 아니라 알림 잡 단위로 재시도를 시도한다.
재시도가 필요한 알림이 존재하는 실패한 알림 잡은 실패 상태로 전이되며 스케줄 서비스에 재시도 스케줄을 등록한다. 
