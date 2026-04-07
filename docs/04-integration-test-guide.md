# 통합 테스트 가이드

`scripts/test-with-server.sh`는 Docker PostgreSQL 기동, Spring Boot 서버 시작, 전체 시나리오 검증을 한 번에 수행합니다.  
**소요 시간 기준: 약 8–12분** (서버 기동 ~8초 + 기본 테스트 ~5분 + Stuck 재기동 + Stuck 시나리오 ~2–3분)

---

## 실행 방법

```bash
# 기본 통합 테스트만 (서버 자동 기동)
./scripts/test.sh               # 서버가 이미 떠있어야 함
./scripts/test-with-server.sh   # 서버 기동부터 Stuck 시나리오까지 전체

# 요청/응답 상세 로그 포함
VERBOSE=1 ./scripts/test-with-server.sh
```

종료 시 서버 유지 여부를 묻습니다. `y`를 입력하면 `http://localhost:8080`에서 수동으로 탐색 가능합니다.

---

## 사전 조건

| 항목     | 내용                                                       |
|--------|----------------------------------------------------------|
| Docker | 실행 중이어야 함 (`docker compose up -d postgres`는 스크립트가 자동 처리) |
| Java   | 17 이상                                                    |
| 포트     | 8080 (기존 프로세스 자동 종료됨)                                    |

---

## 검증 항목 및 소요 시간

### 0. 서버 헬스 체크 (< 1초)

`GET /actuator/health` 응답 확인.

```
[runner] 서버 준비 완료 (7초)
[runner] 애플리케이션 PID=14628
  [PASS] 서버 정상 (http://localhost:8080)
```

---

### [필수 1] 알림 발송 요청 API (~20초)

| 항목                    | 검증 내용                                         |
|-----------------------|-----------------------------------------------|
| 1.0 미발급 키 거절          | 발급받지 않은 `idempotencyKey`로 요청 → HTTP 400       |
| 1.1 Job 생성 Happy Path | 키 발급 → Job 생성 → `SCHEDULED` → `COMPLETED`     |
| 1.2 상태 조회             | `totalCount`, `type`, `metadata` 필드 검증        |
| 1.3 파이프라인 완료 대기       | `COMPLETED` 상태 전이 확인 (최대 30초 폴링)              |
| 1.4 완료 후 상세           | `sentCount == totalCount` (3건)                |
| 사용자 알림 목록             | `GET /api/users/{userId}/notifications` 반환 확인 |
| 개별 알림 조회              | 상태 SENT, 렌더링된 제목 포함 여부 확인                     |

```
  [PASS] 미발급 키 거절: HTTP 400
  [PASS] 미발급 키 오류 메시지: 발급되지 않은 멱등성 키입니다. ...
  [PASS] Job 생성: HTTP 201
  [PASS] Job ID: 829003256322418040
  [PASS] 수신자 수: 3
  [PASS] 알림 타입: ENROLLMENT_COMPLETE
  [PASS] 참조 데이터 (eventId): evt-req1-happy-1775486578
    현재 상태: SCHEDULED
  [LOG] [Orchestrator] Starting send for job 829003256322418040 (triggeredBy=system:execution-handler)
  [LOG] [Orchestrator] Job 829003256322418040 — targets: 3
  [LOG] [SendExecutor] channel=EMAIL — sent: 3, failed: 0
  [LOG] [Orchestrator] Job 829003256322418040 resolved: PROCESSING → COMPLETED
  [PASS] Happy Path 완료: status=COMPLETED (10s)
  [PASS] sentCount == totalCount: 3
  [PASS] 사용자 1001 알림 수: 9
  [PASS] 알림 상태: SENT
  [PASS] 렌더링된 제목: 안녕하세요 수신자A님
```

---

### [필수 3] 중복 발송 방지 (< 10초)

| 항목              | 검증 내용                          |
|-----------------|--------------------------------|
| 3.1 동일 키 재요청    | HTTP 200 + 기존 Job ID 그대로 반환    |
| 3.2 동시 요청 3건 병렬 | 1건 201 생성, 2건 409 충돌 — 락 경합 확인 |

```
  [PASS] 중복 요청 -> 기존 반환: HTTP 200
  [PASS] 동일 Job ID 반환: 829003256322418040
  [PASS] 동시 생성 락 경합 응답 관찰: [201 409 409]
  [PASS] 동시 요청 후 재확인 -> 기존 반환: HTTP 200
```

---

### [필수 4] 비동기 처리 구조 (< 20초)

| 항목            | 검증 내용                                      |
|---------------|--------------------------------------------|
| 4.1 API 즉시 응답 | 응답 직후 상태 `SCHEDULED` 또는 `CREATED` (비동기 정상) |
| 백그라운드 처리      | 이후 폴링으로 `COMPLETED` 전이 확인, 응답 시간 ~103ms    |

```
  [PASS] API 응답: HTTP 201
    API 응답 시간: 103ms
    API 직후 상태: SCHEDULED (CREATED/SCHEDULED = 비동기 정상)
  [PASS] 비동기 처리 완료: status=COMPLETED (8s)
  [PASS] API 즉시 응답 + 백그라운드 처리 확인
```

---

### [선택 2] 알림 템플릿 관리 (< 5초)

| 항목     | API                                | 검증 내용              |
|--------|------------------------------------|--------------------|
| 템플릿 생성 | `POST /api/templates`              | HTTP 201, ID 발급    |
| 템플릿 조회 | `GET /api/templates/{id}`          | code 일치            |
| 목록 조회  | `GET /api/templates?code=`         | 1건 이상 반환           |
| 미리보기   | `POST /api/templates/{id}/preview` | Mustache 변수 렌더링 확인 |

```
  [PASS] 템플릿 생성: HTTP 201
  [PASS] 템플릿 ID: 829003255064126693
  [PASS] 템플릿 code: test-1775486578
  [PASS] 템플릿 목록: 1개
  [PASS] 미리보기 제목: 안녕하세요 김철수님
  [PASS] 미리보기 본문: 김철수님, 주문 ORD-999 알림입니다.
```

---

### [필수 2] 알림 처리 상태 관리 (~60–90초)

| 항목                | 설정                  | 검증 내용                                                     |
|-------------------|---------------------|-----------------------------------------------------------|
| 2.1 TRANSIENT 재시도 | mock 실패율 30%        | `RETRYING` 전이 후 재시도 성공 → `COMPLETED`                      |
| 2.2 PERMANENT 실패  | mock 100% PERMANENT | `deadLetterCount == 2`, Job `FAILED`                      |
| 2.3 취소            | —                   | `SCHEDULED` → `CANCELLED`, 재취소 시 `204` 유지                 |
| 2.4 Max Retry 초과  | mock 100% TRANSIENT | 3회 재시도 소진 → `deadLetterCount == 1`, Job `FAILED` (최대 90초) |
| 2.5 취소 거절         | —                   | `COMPLETED`/`FAILED` Job 취소 시 HTTP 400                    |

> **2.4 소요 시간**: `base-delay=5s` × 지수 백오프 × 3회 ≒ 30–35초

```
--- 2.1 재시도 정책 ---
  [PASS] 첫 발송 후 상태: status=RETRYING (15s)
  [PASS] 재시도 후 완료: status=COMPLETED (4s)

--- 2.2 PERMANENT 실패 ---
  [LOG] [Orchestrator] Job 829003587437321818 — deadLetters: 2, retryable: 0
  [LOG] [Orchestrator] Job 829003587437321818 resolved: PROCESSING → FAILED
  [PASS] PERMANENT -> Job FAILED: status=FAILED (9s)
  [PASS] deadLetterCount (DEAD_LETTER): 2
  [PASS] 실패 사유 기록: 유효하지 않은 수신자 주소 (MOCK PERMANENT)

--- 2.3 취소 ---
  [PASS] Cancel 요청: HTTP 204
  [PASS] Cancel 후 상태: CANCELLED
  [PASS] 이미 CANCELLED 재취소: HTTP 204

--- 2.4 Max Retry 초과 ---
  [LOG] [Orchestrator] Job 829003168885375280 — deadLetters: 0, retryable: 1  ← 1차 실패
  [LOG] [Orchestrator] Job 829003168885375280 — retry scheduled at ...+5s
  [LOG] [Orchestrator] Job 829003168885375280 — deadLetters: 0, retryable: 1  ← 2차 실패
  [LOG] [Orchestrator] Job 829003168885375280 — retry scheduled at ...+10s
  [LOG] [Orchestrator] Job 829003168885375280 — deadLetters: 1, retryable: 0  ← 3차: 재시도 소진
  [PASS] Max Retry 소진 -> FAILED: status=FAILED, deadLetterCount=1 (34s)

--- 2.5 취소 거절 ---
  [PASS] COMPLETED 취소 거절: HTTP 400
  [PASS] COMPLETED 취소 오류 메시지: 취소 불가 상태입니다. 현재 상태: COMPLETED
  [PASS] FAILED 취소 거절: HTTP 400
```

---

### [필수 5] 운영 시나리오 (~30초)

| 항목                 | 검증 내용                                                    |
|--------------------|----------------------------------------------------------|
| 5.1 FAILED → 수동 복구 | `POST /api/notification-jobs/{id}/recover` → `COMPLETED` |
| 5.2 CANCELLED → 복구 | 동일 API → `COMPLETED`                                     |
| 5.3 RETRYING 상태 취소 | RETRYING 중 DELETE → 경쟁 조건 확인                             |

```
  [PASS] FAILED 복구 요청: HTTP 200
  [PASS] FAILED -> 복구 후 COMPLETED: status=COMPLETED (5s)
  [PASS] CANCELLED 복구 요청: HTTP 200
  [PASS] CANCELLED -> 복구 후 COMPLETED: status=COMPLETED (4s)
  [PASS] RETRYING Cancel 시나리오 확인 (경쟁 조건)
```

---

### [선택 3] 읽음 처리 (~5초)

| 항목                  | 검증 내용                          |
|---------------------|--------------------------------|
| 디바이스 1 (iPhone) 읽음  | HTTP 202, `firstReadAt` 설정 확인  |
| 디바이스 2 (MacBook) 읽음 | `firstReadAt` 값 변경 없음 (멱등)     |
| 읽음/안읽음 필터           | `?read=true/false` 파라미터로 분리 조회 |

```
  [PASS] 디바이스 1 읽음: HTTP 202
  [PASS] firstReadAt 설정됨: 2026-04-06T23:33:49.468697+09:00
  [PASS] 디바이스 2 읽음: HTTP 202
  [PASS] firstReadAt 변경 안됨 (멱등): 2026-04-06T23:33:49.468697+09:00
  [PASS] 읽은 알림 필터: 2개
    안읽은 알림: 6개
```

---

### [선택 1] 예약 발송 스케줄링 (~15초)

| 항목        | 검증 내용                                |
|-----------|--------------------------------------|
| 예약 Job 생성 | `scheduledAt` 미래 시각 → `SCHEDULED` 상태 |
| 스케줄 취소    | 발송 전 DELETE → `CANCELLED`            |

```
  [PASS] 예약 발송 검증 완료
  [PASS] 예약 취소 검증 완료
```

---

### [선택 4] 최종 실패 수동 재시도 (~20초)

| 항목                         | 검증 내용                            |
|----------------------------|----------------------------------|
| PERMANENT 실패 → DEAD_LETTER | Job `FAILED` 후 `recover` 호출      |
| `sendTryCount` 리셋          | 재시도 후 `sendTryCount` 초기화, 재발송 성공 |

```
  [PASS] sendTryCount 리셋 후 재발송 성공
```

---

### [추가] Stuck Processing Recovery (~2–3분)

기본 테스트 통과 후 서버를 재기동해 시뮬레이션합니다.

**시나리오**:

1. mock email hang rate 100% 설정
2. Job 생성 → `PROCESSING` 진입 + 발송 시작 로그 확인 (`targets: 1`)
3. 서버 강제 종료 (`kill -9`) — `NotificationSendingStatePersister`의 `REQUIRES_NEW` 커밋 덕분에 `SENDING` 상태가 DB에 남음
4. `--notification.stuck-recovery.stuck-timeout-seconds=5` 설정으로 재기동
5. `StuckProcessingRecoveryScheduler`가 stuck PROCESSING Job 감지 → SENDING notification을 `DEAD_LETTER`로 전이 → Job `FAILED`

```
[runner] Stuck Job 생성 완료 (jobId=829003802229712184)
[runner] PROCESSING 상태와 발송 시작 로그 대기
[runner] Stuck 진입 상태 확인 (job=PROCESSING, send-start logged)
[runner] Stuck 시나리오를 위해 애플리케이션 강제 종료
[runner] 서버 시작 (--notification.stuck-recovery.stuck-timeout-seconds=5 ...)
[runner] 서버 준비 완료 (6초)
  [LOG] [Recoverer:StuckProcessing] Found 1 stuck PROCESSING jobs (timeout=5s)
  [LOG] [Recoverer:StuckProcessing] Job 829003802229712184 recovered (PROCESSING → FAILED, failedSending=1)
[runner] Stuck recovery 완료: job=FAILED, notification=DEAD_LETTER
[runner] Stuck recovery 사유 기록: Stuck SENDING recovery (timeout=5s)
```

---

## 최종 결과 요약

실제 실행 결과 (PASS: 74, FAIL: 0, SKIP: 0):

```
  PASS: 74
  FAIL: 0
  SKIP: 0

  모든 구현 요구사항 검증 통과!

────────────────────────────────────────────────────────
[runner] 기본 통합 테스트 통과
[runner] Stuck recovery 테스트 통과
```

---

## 타임라인 요약

```
00:00  서버 기동 시작
00:08  서버 준비 완료 → 기본 테스트 시작
00:30  [필수 1] Happy Path 완료
00:35  [선택 2] 템플릿 CRUD 완료
01:00  [필수 3] 중복 방지 완료
01:30  [필수 4] 비동기 확인 완료
03:30  [필수 2] 재시도 / 실패 / Max Retry 완료  ← 가장 오래 걸리는 구간 (~2분)
04:00  [필수 5] 운영 시나리오 완료
04:10  [선택 3] 읽음 처리 완료
04:30  [선택 1] 스케줄링 완료
04:50  [선택 4] 수동 재시도 완료
       PASS: 74 / FAIL: 0 / SKIP: 0
04:50  기본 테스트 완료 → Stuck 시나리오용 서버 재기동
05:00  Stuck 서버 준비
05:30  Job PROCESSING 진입 확인 → kill -9 크래시
05:40  복구용 서버 재기동
05:50  StuckProcessingRecoveryScheduler 감지 → DEAD_LETTER 전이
06:00  Stuck 시나리오 완료
```

---

## 로그 확인

```bash
# 실행 중 실시간 로그 (Orchestrator, Relay 등 주요 이벤트 필터링)
tail -f /tmp/notification-server-test.log | grep -E '\[(UC:|Handler:|Relay:|Orchestrator|SendExecutor)\]'

# 전체 로그
tail -f /tmp/notification-server-test.log
```

---

## 환경 변수

| 변수               | 기본값                     | 설명                    |
|------------------|-------------------------|-----------------------|
| `BASE_URL`       | `http://localhost:8080` | 서버 주소                 |
| `VERBOSE`        | `0`                     | `1`로 설정 시 요청/응답 전체 출력 |
| `KEEP_SERVER`    | `n`                     | `y`로 설정 시 테스트 후 서버 유지 |
| `RUN_BASE_TESTS` | `y`                     | `n`으로 설정 시 기본 테스트 건너뜀 |
