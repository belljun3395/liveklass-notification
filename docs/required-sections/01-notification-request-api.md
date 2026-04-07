# 1. 알림 발송 요청 API

## 설계 의도

알림 발송 요청 API는 외부 채널로 즉시 보내는 구조가 아니라, 먼저 **알림 잡 생성 요청을 접수**하고 이후 비동기 파이프라인에서 처리하는 구조로 설계했습니다. 현재 구현 기준으로 API의 역할은 발송 완료를 보장하는 것이 아니라, 발송 파이프라인에 안전하게 등록하는 것입니다.

---

## 제공 API

- 알림 잡 관련 API
    - 멱등성 키 발급(추가): `POST /api/notification-jobs/key`
    - 알림 잡 생성(필수): `POST /api/notification-jobs`
    - 알림 잡 조회(추가): `GET /api/notification-jobs/{jobId}`
    - 알림 잡 취소(추가): `DELETE /api/notification-jobs/{jobId}`
    - 알림 잡 재시도(추가): `POST /api/notification-jobs/{jobId}/recover`

- 알림 관련 API
    - 개별 알림 조회(필수): `GET /api/notifications/{id}`
    - 사용자 알림 목록 조회(필수): `GET /api/users/{userId}/notifications`
    - 읽음 처리(필수): `PATCH /api/notifications/{id}/read`

## 알림 발송 요청 등록

**[Step 0] 알림 잡 생성을 위한 멱등성 키 발급 — `IssueCreationJobKeyUseCase`**

```
IssueCreationJobKeyUseCase.execute()
  └─ idempotencyStore.generateKey()   → 고유 키 생성 + 발급 이력 등록
  └─ 반환: idempotencyKey
```

```bash
# Request
curl -X POST http://localhost:8080/api/notification-jobs/key

# Response 200
{
  "data": {
    "idempotencyKey": "nk_01J9Z8K2M3X4Y5Z6W7V8U9T0R1"
  }
}
```

**[Step 1] 알림 잡 생성 — `CreateScheduledNotificationJobUseCase`**

```
CreateScheduledNotificationJobUseCase.execute(idempotencyKey, ...)
  ├─ 1. idempotencyStore.isIssued(key)
  │     └─ 미발급 키: IllegalArgumentException
  ├─ 2. distributedLock.tryLock(key, ttl=10m)
  │     └─ 락 획득 실패: DuplicateResourceException
  ├─ 3. DB에서 동일 키 Job 조회
  │     └─ 이미 존재: 기존 Job 반환 (created=false)
  ├─ 4. templateResolver.resolve(templateCode, channel, locale)
  ├─ 5. NotificationJob.create(...)              → Job: CREATED
  ├─ 6. Notification.create(...) × N명           → Notification: PENDING
  ├─ 7. DB 저장 (job + notifications)
  ├─ 8. NotificationJobCreatedEvent 발행
  └─ 9. distributedLock.unlock(key, token)       (finally)
```

```bash
# Request
curl -X POST http://localhost:8080/api/notification-jobs \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "nk_01J9Z8K2M3X4Y5Z6W7V8U9T0R1",
    "channel": "EMAIL",
    "templateCode": "welcome-email",
    "locale": "ko",
    "type": "MARKETING",
    "metadata": { "campaignId": "camp_001" },
    "scheduledAt": "2025-01-01T09:00:00+09:00",
    "recipients": [
      {
        "recipientId": 1001,
        "contact": "user@example.com",
        "variables": { "name": "홍길동" }
      }
    ]
  }'

# Response 201 (신규 생성)
{
  "data": {
    "jobId": "123456789012345678",
    "channel": "EMAIL",
    "status": "CREATED",
    "type": "MARKETING",
    "metadata": { "campaignId": "camp_001" },
    "scheduleHistory": [
      { "type": "INITIAL", "scheduledAt": "2025-01-01T09:00:00+09:00", "executed": false }
    ],
    "totalCount": 1,
    "pendingCount": 1,
    "sendingCount": 0,
    "sentCount": 0,
    "failedCount": 0,
    "retryWaitingCount": 0,
    "deadLetterCount": 0,
    "cancelledCount": 0,
    "createdAt": "2024-12-31T19:00:00+09:00",
    "lastStatusChangeReason": null
  }
}

# Response 200 (중복 요청 — 기존 Job 반환)
```

## 알림 상태 조회

**`GET /api/notifications/{id}`**

```bash
# Request
curl http://localhost:8080/api/notifications/123456789012345678

# Response 200
{
  "data": {
    "id": "123456789012345678",
    "jobId": "987654321098765432",
    "recipientId": "1001",
    "channel": "EMAIL",
    "status": "SENT",
    "type": "MARKETING",
    "metadata": { "campaignId": "camp_001" },
    "attemptCount": 1,
    "lastFailureReason": null,
    "firstReadAt": null,
    "createdAt": "2024-12-31T19:00:00+09:00",
    "updatedAt": "2025-01-01T09:00:05+09:00",
    "renderedTitle": "안녕하세요, 홍길동님",
    "renderedBody": "신년 이벤트에 초대합니다."
  }
}
```

> `id`, `jobId`, `recipientId`는 TSID 기반 64비트 정수로 JS `Number.MAX_SAFE_INTEGER`를 초과하므로 문자열로 직렬화됩니다.

## 사용자 알림 목록 조회

**`GET /api/users/{userId}/notifications`**

커서 기반 페이지네이션을 사용합니다. `read` 필터로 읽음/미읽음 여부를 지정할 수 있습니다.

```bash
# Request — 미읽음 목록, 20건씩
curl "http://localhost:8080/api/users/1001/notifications?read=false&size=20"

# Request — 다음 페이지 (cursor 사용)
curl "http://localhost:8080/api/users/1001/notifications?read=false&size=20&cursor=123456789012345678"

# Response 200
{
  "data": {
    "items": [
      {
        "id": "123456789012345678",
        "jobId": "987654321098765432",
        "recipientId": "1001",
        "channel": "EMAIL",
        "status": "SENT",
        "type": "MARKETING",
        "metadata": {},
        "attemptCount": 1,
        "lastFailureReason": null,
        "firstReadAt": null,
        "createdAt": "2025-01-01T09:00:00+09:00",
        "updatedAt": "2025-01-01T09:00:05+09:00",
        "renderedTitle": "안녕하세요",
        "renderedBody": "신년 이벤트에 초대합니다."
      }
    ],
    "nextCursor": "123456789012344444",
    "hasNext": true
  }
}
```
