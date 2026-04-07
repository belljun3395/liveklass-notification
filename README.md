# liveklass-notification

## 목차

- [프로젝트 개요](#프로젝트-개요)
- [기술 스택](#기술-스택)
- [실행 방법](#실행-방법)
- [API 목록 및 예시](#api-목록-및-예시)
- [데이터 모델 설명](#데이터-모델-설명)
- [요구사항 해석 및 가정](#요구사항-해석-및-가정)
- [설계 결정과 이유](#설계-결정과-이유)
- [테스트 실행 방법](#테스트-실행-방법)
- [미구현 / 제약사항](#미구현--제약사항)
- [AI 활용 범위](#ai-활용-범위)

---

## 프로젝트 개요

LiveKlass 알림 발송 파이프라인 시스템

예약 발송·즉시 발송·재시도·읽음 처리까지 알림 생애주기를 관리하는 Spring Boot 기반 서버입니다.

---

## 기술 스택

| 분류        | 기술                                                            |
|-----------|---------------------------------------------------------------|
| Language  | Java 21                                                       |
| Framework | Spring Boot 3.4.4                                             |
| Database  | PostgreSQL 16                                                 |
| ORM       | Spring Data JPA / Hibernate                                   |
| 테스트       | JUnit 5, Testcontainers, ArchUnit                             |
| 관찰성       | Micrometer + Prometheus, OpenTelemetry (OTLP), Grafana, Tempo |
| 코드 품질     | Spotless (Google Java Format AOSP)                            |
| 빌드        | Gradle                                                        |
| 컨테이너      | Docker Compose (spring-boot-docker-compose 자동 연동)             |

---

## 실행 방법

### 서버 실행

Docker Desktop이 실행 중인 상태에서 아래 명령을 실행하면,  
Spring Boot가 `compose.yaml`을 자동으로 감지해 PostgreSQL·Adminer·Tempo·Prometheus·Grafana를 함께 기동합니다.

```bash
./gradlew bootRun
```

또는 IDE(IntelliJ)에서 `LiveklassNotificationApplication`을 직접 실행해도 됩니다.

> 서버 기본 포트: **8080**

### Adminer 실행 (DB GUI)

브라우저에서 아래 URL을 열고 다음 정보로 접속합니다.

```
http://localhost:18080
```

| 항목     | 값              |
|--------|----------------|
| 시스템    | PostgreSQL     |
| 서버     | `db`           |
| 사용자명   | `notification` |
| 비밀번호   | `notification` |
| 데이터베이스 | `notification` |

### 관찰성 도구

| 도구              | URL                   |
|-----------------|-----------------------|
| Grafana         | http://localhost:3000 |
| Prometheus      | http://localhost:9090 |
| Tempo (tracing) | http://localhost:3200 |

---

## API 목록 및 예시

응답은 공통 래퍼 `{ "data": ... }` 형식으로 반환됩니다.

### 템플릿 관리

| 메서드      | 경로                                    | 설명          |
|----------|---------------------------------------|-------------|
| `POST`   | `/api/templates`                      | 템플릿 생성      |
| `GET`    | `/api/templates?code={code}`          | 코드로 목록 조회   |
| `GET`    | `/api/templates/{templateId}`         | 단건 조회       |
| `POST`   | `/api/templates/{templateId}/preview` | 변수 바인딩 미리보기 |
| `DELETE` | `/api/templates/{templateId}`         | 소프트 삭제      |

**예시 — 템플릿 생성**

```http
POST /api/templates
Content-Type: application/json

{
  "code": "welcome-demo",
  "channel": "EMAIL",
  "locale": "ko",
  "titleTemplate": "{{name}}님, 가입을 환영합니다!",
  "bodyTemplate": "{{name}}님, LiveKlass에 오신 걸 환영합니다.\n\n지금 바로 {{lectureTitle}}을 수강해보세요.",
  "description": "가입 환영 이메일",
  "variables": [
    {"name": "name",         "dataType": "STRING", "required": true,  "exampleValue": "홍길동",           "description": "수신자 이름"},
    {"name": "lectureTitle", "dataType": "STRING", "required": false, "exampleValue": "Spring Boot 입문", "description": "추천 강좌 제목"}
  ]
}
```

### 알림 Job 관리

| 메서드      | 경로                                       | 설명                |
|----------|------------------------------------------|-------------------|
| `POST`   | `/api/notification-jobs/key`             | 멱등성 키 발급          |
| `POST`   | `/api/notification-jobs`                 | 알림 Job 생성 (예약/즉시) |
| `GET`    | `/api/notification-jobs/{jobId}`         | Job 조회            |
| `DELETE` | `/api/notification-jobs/{jobId}`         | Job 취소            |
| `POST`   | `/api/notification-jobs/{jobId}/recover` | 실패 알림 수동 재시도      |

**예시 — 멱등성 키 발급 후 Job 생성**

```http
POST /api/notification-jobs/key
```

```http
POST /api/notification-jobs
Content-Type: application/json

{
  "idempotencyKey": "{{발급된 키}}",
  "channel": "EMAIL",
  "templateCode": "enrollment-complete",
  "locale": "ko",
  "type": "ENROLLMENT_COMPLETE",
  "metadata": { "eventId": "evt-001", "lectureId": "lec-spring" },
  "scheduledAt": "2026-05-01T10:00:00",
  "recipients": [
    {
      "recipientId": 1001,
      "contact": "user@example.com",
      "variables": {
        "name": "홍길동",
        "lectureTitle": "Spring Boot 마스터 클래스",
        "instructorName": "김강사",
        "startDate": "2026-05-01"
      }
    }
  ]
}
```

### 알림 조회 / 읽음 처리

| 메서드     | 경로                                                                     | 설명                  |
|---------|------------------------------------------------------------------------|---------------------|
| `GET`   | `/api/notifications/{id}`                                              | 단건 조회               |
| `GET`   | `/api/users/{userId}/notifications?read={true\|false}&cursor=&size=20` | 커서 기반 목록 조회         |
| `PATCH` | `/api/notifications/{id}/read?userId={userId}`                         | 읽음 처리 (비동기, 202 반환) |

### Actuator (운영)

| 메서드        | 경로                              | 설명                   |
|------------|---------------------------------|----------------------|
| `GET`      | `/actuator/health`              | 헬스 체크                |
| `GET`      | `/actuator/prometheus`          | Prometheus 메트릭       |
| `GET`      | `/actuator/scheduledtasks`      | 스케줄 태스크 목록           |
| `GET/POST` | `/actuator/mock-email`          | Mock 이메일 발송 설정 조회/변경 |
| `GET`      | `/actuator/application-modules` | 모듈 구조 확인             |

---

## 데이터 모델 설명

### 주요 테이블

#### `notification_jobs`

알림 Job의 메타데이터와 상태를 관리합니다.

| 컬럼                          | 타입                  | 설명                                                    |
|-----------------------------|---------------------|-------------------------------------------------------|
| `id`                        | BIGINT PK           | TSID                                                  |
| `status`                    | VARCHAR(30)         | PENDING / PROCESSING / COMPLETED / CANCELLED / FAILED |
| `title_template`            | VARCHAR             | 제목 템플릿                                                |
| `content_template`          | TEXT                | 본문 템플릿                                                |
| `channel`                   | VARCHAR(20)         | EMAIL 등                                               |
| `notification_type`         | VARCHAR             | 알림 유형                                                 |
| `metadata`                  | JSONB               | 이벤트 메타데이터                                             |
| `idempotency_key`           | VARCHAR(255) UNIQUE | 중복 발송 방지 키                                            |
| `created_at` / `updated_at` | TIMESTAMPTZ         | 생성/수정 일시                                              |
| `deleted`                   | BOOLEAN             | 소프트 삭제                                                |

#### `notifications`

수신자별 개별 알림 레코드입니다.

| 컬럼                            | 타입          | 설명                             |
|-------------------------------|-------------|--------------------------------|
| `id`                          | BIGINT PK   | TSID                           |
| `job_id`                      | BIGINT      | 연결된 Job                        |
| `recipient_id`                | BIGINT      | 수신자 ID                         |
| `recipient_contact`           | VARCHAR     | 수신자 연락처 (이메일 등)                |
| `variables`                   | JSONB       | 템플릿 변수                         |
| `status`                      | VARCHAR(30) | PENDING / SENT / FAILED / READ |
| `send_try_count`              | INT         | 발송 시도 횟수                       |
| `last_failure_classification` | VARCHAR     | 실패 분류 (TRANSIENT / PERMANENT)  |
| `first_read_at`               | TIMESTAMPTZ | 최초 읽음 일시                       |

#### `notification_contents`

렌더링된 알림 제목/본문을 저장합니다.

| 컬럼                | 타입            | 설명      |
|-------------------|---------------|---------|
| `notification_id` | BIGINT UNIQUE | 알림 FK   |
| `rendered_title`  | VARCHAR(500)  | 렌더링된 제목 |
| `rendered_body`   | TEXT          | 렌더링된 본문 |

#### `scheduled_notification_jobs`

예약 발송 스케줄을 관리합니다.

| 컬럼              | 타입          | 설명                    |
|-----------------|-------------|-----------------------|
| `job_id`        | BIGINT      | 연결된 Job               |
| `schedule_type` | VARCHAR(20) | SCHEDULED / IMMEDIATE |
| `scheduled_at`  | TIMESTAMPTZ | 예약 발송 일시              |
| `executed`      | BOOLEAN     | 실행 여부                 |

#### `notification_send_histories`

발송 시도 이력입니다.

| 컬럼                          | 타입          | 설명       |
|-----------------------------|-------------|----------|
| `notification_id`           | BIGINT      | 알림 FK    |
| `status`                    | VARCHAR     | 결과 상태    |
| `attempt_no`                | INT         | 시도 번호    |
| `from_status` / `to_status` | VARCHAR     | 상태 전이 기록 |
| `failure_code`              | VARCHAR(50) | 실패 코드    |

#### `notification_read_events`

읽음 이벤트를 기기 단위로 기록합니다.

| 컬럼                            | 타입           | 설명        |
|-------------------------------|--------------|-----------|
| `notification_id` / `user_id` | BIGINT       | 알림·사용자 FK |
| `device_id`                   | VARCHAR(255) | 기기 식별자    |
| `device_type`                 | VARCHAR(50)  | 기기 유형     |
| `read_at`                     | TIMESTAMPTZ  | 읽음 일시     |

#### `notification_templates`

알림 템플릿을 코드·채널·로케일·버전으로 관리합니다.

| 컬럼                                 | 타입             | 설명         |
|------------------------------------|----------------|------------|
| `code`                             | VARCHAR(100)   | 템플릿 코드     |
| `channel`                          | VARCHAR(20)    | 채널         |
| `locale`                           | VARCHAR(10)    | 로케일 (ko 등) |
| `version`                          | INT            | 버전         |
| `title_template` / `body_template` | VARCHAR / TEXT | 템플릿 본문     |
| `variables`                        | TEXT (JSON)    | 변수 목록      |

> **기본 제공 템플릿**: `enrollment-complete`, `lecture-reminder`, `payment-complete` (서버 시작 시 자동 등록)

---

## 요구사항 해석 및 가정

### 알림 발송 단위

요구사항의 "알림 발송"을 **Job(발송 작업) → Notification(수신자별 알림)** 2계층으로 해석했습니다.
하나의 Job이 다수 수신자에게 동일 템플릿·채널로 알림을 발송하며, 수신자별 변수 바인딩·상태 추적·재시도가 독립적으로 관리됩니다.

### 예약 발송 전제

모든 알림 Job은 예약 발송(`scheduledAt`)을 기본으로 합니다.
`scheduledAt`은 반드시 현재 시각 이후여야 하며(`scheduledAt > now` 검증),
즉시 발송이 필요한 경우 가까운 미래 시각(예: 현재 + 수 초)을 지정합니다.
`DbScheduledNotificationJobRelay`가 `fixedDelay` 주기(기본 2초)로 도달한 스케줄을 감지하여 처리하므로,
현재 시각 직후로 지정하면 다음 폴링 사이클에서 바로 발송이 시작됩니다.

별도의 "즉시 발송" API를 두지 않은 이유는, 예약/즉시를 단일 파이프라인으로 통합하여
상태 관리·재시도·중복 방지 로직의 분기를 줄이기 위함입니다.

### 멱등성 키 서버 발급

클라이언트가 임의 키를 사용할 경우 충돌·악용 가능성이 있어, 서버가 키를 발급하고 검증하는 2단계 패턴을 채택했습니다.
네트워크 재시도나 클라이언트 중복 호출 시에도 동일 요청이 중복 처리되지 않습니다.

### 재시도 정책

실패한 알림은 `TRANSIENT`/`PERMANENT`으로 분류됩니다.
`TRANSIENT` 실패는 지수 백오프로 자동 재시도되며, 최대 재시도 횟수 초과 시 `DEAD_LETTER`로 격리됩니다.
`PERMANENT` 실패는 즉시 `DEAD_LETTER`로 전이됩니다.
`DEAD_LETTER` 알림은 수동 복구(`/recover`)로 `sendTryCount`를 초기화하여 재시도할 수 있습니다.

### 읽음 처리

읽음 처리는 비동기로 수행됩니다. `firstReadAt` 필드에 최초 읽음 시각만 기록하며, 이후 읽음 요청은 기존 값보다 이른 시각일 때만 갱신됩니다(`LEAST` 함수).
기기별 읽음 이력은 `notification_read_events` 테이블에 별도 기록됩니다.

### 타임존

내부 저장·비교는 UTC 기준으로 통일합니다. DB는 `TIMESTAMPTZ`를 사용하여 instant가 보존됩니다.
API 응답은 `notification.response-timezone` 프로퍼티(기본값 `+09:00`, KST)로 변환하여 반환합니다.

---

## 설계 결정과 이유

상세 설계 문서는 `docs/` 디렉토리에 있습니다.

| 문서                                                                                        | 다루는 내용                                                          |
|-------------------------------------------------------------------------------------------|-----------------------------------------------------------------|
| [01-job-creation-and-scheduling-flow.md](docs/01-job-creation-and-scheduling-flow.md)     | Job 생성 → SCHEDULED → PROCESSING 흐름, 2단계 키 발급, 릴레이 격리            |
| [02-job-processing-and-completion-flow.md](docs/02-job-processing-and-completion-flow.md) | 발송 파이프라인, Orchestrator 조율, Watchdog 락 갱신, 채널별 발송 전략             |
| [03-job-cancellation-and-recovery-flow.md](docs/03-job-cancellation-and-recovery-flow.md) | 취소·수동 복구·Stuck PROCESSING 자동 복구 흐름                              |
| [04-integration-test-guide.md](docs/04-integration-test-guide.md)                         | 통합 테스트 실행 방법 및 시나리오                                             |
| [05-state-machine-design.md](docs/05-state-machine-design.md)                             | JobStatus·NotificationStatus 상태 머신, JobNotificationPolicy 정책 설계 |
| [06-notification-job-lifecycle.adoc](docs/06-notification-job-lifecycle.adoc)             | NotificationJob 생명주기 종합 — 실행 주체별 상태 전이·정책·동시성 제어                |

### 필수 구현 사항 문서

| 구현 항목           | 문서                                                                                                |
|-----------------|---------------------------------------------------------------------------------------------------|
| 1. 알림 발송 요청 API | [01-notification-request-api.md](docs/required-sections/01-notification-request-api.md)           |
| 2. 알림 처리 상태 관리  | [02-notification-state-management.md](docs/required-sections/02-notification-state-management.md) |
| 3. 중복 발송 방지     | [03-duplicate-prevention.md](docs/required-sections/03-duplicate-prevention.md)                   |
| 4. 비동기 처리 구조    | [04-async-processing-structure.md](docs/required-sections/04-async-processing-structure.md)       |
| 5. 운영 시나리오 대응   | [05-operational-scenarios.md](docs/required-sections/05-operational-scenarios.md)                 |

---

## 테스트 실행 방법

### 단위/통합 테스트 실행

```bash
./gradlew test
```

Testcontainers가 PostgreSQL 컨테이너를 자동으로 실행하므로 Docker가 필요합니다.

### 상태 전이 문서 자동 생성

```bash
./gradlew generateStateDocs
```

### 이벤트 흐름 다이어그램 자동 생성

```bash
./gradlew generateEventFlowDiagrams
```

### HTTP 스크립트 실행 (IntelliJ HTTP Client)

`http/` 디렉토리의 스크립트를 실행 전에 환경을 **`local`** 로 설정해야 합니다.

```
http/http-client.env.json → "local" 환경 선택
```

| 파일                          | 설명                              |
|-----------------------------|---------------------------------|
| `01-templates.http`         | 템플릿 생성·조회·미리보기                  |
| `02-notification-jobs.http` | Job 생성(단일/다중 수신자)·조회·취소·재시도     |
| `03-notifications.http`     | 알림 목록 조회·읽음 처리                  |
| `04-mock-email.http`        | Mock 이메일 발송 설정 (실패율·지연·hang 주입) |

**실행 순서**: `01` → `02` → `03` 순으로 실행하면 앞 단계의 응답 값이 전역 변수에 자동 설정됩니다.

---

## 미구현 / 제약사항

### 과제 제약 사항

> 실제 이메일 발송 불필요 (Mock 또는 로그 출력으로 대체)
> 실제 메시지 브로커 설치 불필요. 단, 실제 운영 환경으로 전환 가능한 구조여야 함

위 제약을 준수하면서 운영 환경으로 전환 가능한 구조를 유지하기 위해 아래와 같이 구현했습니다.

---

#### 다중 인스턴스 대비

동시성 제어는 **DB 레벨 잠금**과 **애플리케이션 레벨 분산 락** 2계층으로 설계되어 있습니다.

DB 레벨 잠금(`SELECT FOR UPDATE`, `SELECT FOR UPDATE SKIP LOCKED`)은 인메모리 구현과 무관하게
다중 인스턴스 환경에서도 동일하게 동작합니다.
모든 상태 전이 지점에서 비관적 잠금을 사용하므로, 동일 알림 잡에 대한 동시 처리가 DB 수준에서 차단됩니다.

애플리케이션 레벨 분산 락(`DistributedLock`)과 멱등성 저장소(`IdempotencyStore`)는 현재 인메모리 구현이며,
다중 인스턴스 전환 시 Redis 구현체로 교체하면 됩니다. 인터페이스 계약이 동일하므로 교체 외 코드 변경은 없습니다.

#### 분산 락 — `InMemoryDistributedLock`

브로커/Redis 없이 `ConcurrentHashMap` 기반으로 구현되어 있습니다.
단일 인스턴스에서는 JVM 레벨의 원자성이 보장되며, `DistributedLock` 인터페이스로 추상화되어 있어
운영 전환 시 Redis 구현체(`SET key token NX PX ttl`)로 교체하면 됩니다.
인터페이스에 TTL 파라미터가 이미 포함되어 있어 Redis `PX` 옵션과 직접 대응됩니다.

#### 멱등성 저장소 — `InMemoryIdempotencyStore`

발급된 키를 `ConcurrentHashMap`으로 관리합니다.
`IdempotencyStore` 인터페이스로 추상화되어 있어 Redis 구현체로 교체 가능합니다.
다중 인스턴스 환경으로 전환 시 키 만료(TTL) 또는 잡 라이프사이클 종료 시 삭제 로직이 추가로 필요합니다.

#### 이벤트 발행 — Spring Modulith `@ApplicationModuleListener`

외부 브로커(Kafka 등) 없이 Spring 내장 이벤트 시스템을 사용합니다.
단, 모든 이벤트 리스너가 `@ApplicationModuleListener`를 통해 등록되어 있어
Spring Modulith의 `EventPublicationRegistry`가 이벤트 발행 레코드를 DB에 저장합니다.
재시작 후 미완료 이벤트는 Modulith에 의해 자동 재발행되므로 순수 인메모리 이벤트 시스템이 아닙니다.

`NotificationEventPublisher` 인터페이스로 발행 계층이 추상화되어 있어
운영 전환 시 `SpringEventAdapter` 대신 Kafka/RabbitMQ 어댑터 구현체를 등록하면 됩니다.

#### 스케줄링 — DB 기반 폴링 (`scheduled_notification_jobs`)

외부 스케줄러(Quartz 등) 없이 `scheduled_notification_jobs` 테이블을 폴링하는 방식으로 구현됩니다.
영속성이 DB에 있어 재시작 후에도 미실행 스케줄은 유지됩니다.
이 방식은 운영 환경에서도 추가 인프라 없이 동일하게 사용 가능합니다.

#### 이메일 발송 — `MockEmailSender`

실제 SMTP/SES에 연동하지 않습니다.
`MockEmailSender`는 설정 가능한 실패율·응답 지연·hang 주입을 지원하여
발송 실패 복구, 재시도 백오프, stuck 복구 등의 시나리오를 테스트할 수 있습니다.
운영 전환 시 `NotificationService` 인터페이스 구현체를 교체하면 됩니다.

---

#### 운영 환경 전환 시 필요한 작업 요약

| 항목      | 현재 구현                      | 운영 전환 대상                               |
|---------|----------------------------|----------------------------------------|
| 분산 락    | `InMemoryDistributedLock`  | `RedisDistributedLock`                 |
| 멱등성 저장소 | `InMemoryIdempotencyStore` | `RedisIdempotencyStore` (TTL/삭제 추가 필요) |
| 이벤트 발행  | `SpringEventAdapter`       | Kafka/RabbitMQ 어댑터                     |
| 이메일 발송  | `MockEmailSender`          | 실제 SMTP/SES 구현체                        |
| 스케줄링    | DB 폴링                      | 변경 불필요 (DB 폴링으로 운영 가능)                 |

---

## AI 활용 범위

이 프로젝트에서 AI는 **설계 의사결정의 보조 도구**로 활용했습니다.
핵심 설계(도메인 모델링, 상태 머신, 동시성 제어 전략)는 직접 수행하고, AI에는 컨벤션에 맞는 코드 초안 작성과 테스트 시나리오 도출을 위임하는 방식입니다.

### 1. 설계 단계 — 컨벤션 정의와 구조 검증

프로젝트 초기에 AI와 함께 개발 컨벤션을 정의하고, 이를 코드와 테스트로 고정했습니다.

- **네이밍 컨벤션**: API 하나당 하나의 UseCase, DTO는 `XXXUseCaseIn/Out` 형식
- **패키지 구조**: `application`, `domain`, `repository`, `service`로 역할 분리
- **구조 검증 자동화**: [ArchUnit](https://www.archunit.org/)으로 아키텍처 규칙을 테스트
  코드([ArchitectureRulesTest.java](src/test/java/com/notification/ArchitectureRulesTest.java))로 구현하여 구조적 일관성을 자동 검증

컨벤션을 문서가 아닌 **코드와 테스트로 표현**한 것이 핵심입니다.
이후 AI에게 초안 작성을 요청할 때 컨벤션에 맞는 일관된 코드가 나오고,
사람이 코드를 읽을 때도 별도 문서 참조 없이 패턴을 파악할 수 있습니다.

### 2. 구현 단계 — 설계는 직접, 초안은 AI

구현은 **설계 → AI 초안 → 개선**의 3단계로 진행했습니다.

**직접 수행한 영역:**

- 도메인 모델링 (`NotificationJob`/`Notification` 2계층 분리)
- API 설계 및 상태 전이 규칙 정의

**AI에 위임한 영역:**

- 정의된 스펙에 맞는 코드 초안 작성
- 컨벤션에 맞는 반복적 구현 (Repository, Controller 등)

**개선 과정의 예시 — 상태 머신 도입:**

알림 관리에서 `NotificationJob`과 `Notification`의 상태를 적합한 규칙에 따라 전이시키는 것이 핵심이었습니다.
처음에는 상태 전이 규칙을 문서로 관리했지만, 코드 변경과 문서의 불일치가 발생할 수 있어 AI와 개선안을 논의했습니다.
결과적으로 `JobNotificationPolicy` enum을 도입하여 **Job 상태 전이와 Notification 전이 허용 목록을 하나의 정책 상수로 선언**하는 구조로 발전시켰습니다.

문서도 중요하지만 **코드와 테스트로 의도를 표현**하는 것이 AI와 사람 모두에게 더 명확한 소통 수단이 된다 생각합니다.

### 3. 테스트 단계 — 시나리오 도출과 검증 자동화

테스트 작성에서 AI의 기여가 가장 컸습니다.

- **통합 테스트**: 여러 컴포넌트가 협력하는 시나리오(정상 발송, 실패 재시도, 취소 후 복구, Stuck 복구 등)의 테스트 케이스와 코드를 AI에게 초안 작성 요청
- **시나리오 테스트 스크립트**: 다양한 엣지 케이스에 대한 HTTP 스크립트를 AI와 함께 작성

### 4. AI 활용에 대한 생각

AI를 활용할 때 가장 중요한 것은 **AI가 의도대로 동작하는지 검증하는 것**이라고 생각합니다.

이 프로젝트에서는 테스트 코드와 ArchUnit 규칙이 그 역할을 했습니다.
별도로 개인 프로젝트 [agent-tracer](https://github.com/belljun3395/agent-tracer)를 만들어 AI의 행동을 관찰할 수 있는 도구를 개발하기도 했습니다.
이 과제에서 직접 사용하지는 않았지만, AI가 생성한 코드가 프롬프트의 의도와 일치하는지 체계적으로 확인하는 과정이 AI 활용의 품질을 결정한다고 생각합니다.
