# 4. 비동기 처리 구조

## 알림 발송 비동기 처리

알림 발송 기능은 API 요청과는 별도의 비동기 파이프라인으로 처리하도록 설계하였습니다.

알림 발송 API는 요청을 접수하는 역할만 담당하고, 실제 발송은 이벤트 기반으로 비동기 처리됩니다.

알림 발송을 위한 파이프라인은 다음과 같이 구성되어 있습니다.

- API: 알림 발송 요청을 접수하고, `NotificationJob`과 `Notification`을 DB에 저장한 후 `NotificationJobCreatedEvent`를 발행합니다.
- 알림 생성 핸들러: `NotificationJobCreatedEvent`를 수신하여 `NotificationJob`을 `SCHEDULED`로 전이시키고, 발송 스케줄을 등록합니다.
- 알림 스케줄 릴레이: 등록된 스케줄에 따라 실행 시점이 도달한 `NotificationJob`을 `PROCESSING`으로 전이시킵니다.
    - 릴레이에서 `NotificationJob`의 상태 전이만 수행하고, 실제 발송은 다음 단계에서 처리하도록 분리했습니다.
    - 릴레이를 도입한 이유는 스케줄 서비스에 따라 다양한 방법으로 서버에게 알림 발송 시점을 알려줄 수 있기 때문입니다.
- 알림 잡 실행 스케줄러: `PROCESSING` 상태의 `NotificationJob`에 대해 `NotificationJobExecutionEvent`를 발행합니다.
- 알림 잡 실행 핸들러: `NotificationJobExecutionEvent`를 수신하여 알림 잡을 기준으로 알림을 발송합니다.

## 핸들러

핸들러는 어댑터와 프로세서로 분리되어 있습니다. 어댑터는 이벤트 수신만 담당하고, 실제 처리 로직은 프로세서에 위임합니다. 현재 구현에서는 Job 생성/실행 관련 어댑터는 Spring Modulith `@ApplicationModuleListener`를 사용하고, 일부 후행 이벤트 처리 어댑터는 `@EventListener`를 사용합니다.

```java
// 어댑터 — 이벤트 수신 및 위임만 담당
@ApplicationModuleListener
public void handle(NotificationJobCreatedEvent event) {
    processor.process(event);
}

// 프로세서 — 실제 처리 로직
public void process(NotificationJobCreatedEvent event) {
    // 1. 멱등성 보장: 동일 키로 중복 이벤트 수신 시 skip
    Optional<String> lockToken = distributedLock.tryLock(event.idempotencyKey(), idempotencyTtl);
    if (lockToken.isEmpty()) { return; }
    try {
        // 2. Job 조회 및 SCHEDULED 전이
        NotificationJob job = jobRepository.findByIdAndDeletedFalse(event.jobId()).orElseThrow(...);
        job.markScheduled();
        // 3. 스케줄 등록
        schedulePort.schedule(job.getId(), event.scheduledAt());
    } finally {
        distributedLock.unlock(event.idempotencyKey(), lockToken.get());
    }
}
```

이 구조 덕분에 현재는 Spring 이벤트 및 Spring Modulith 기반으로 동작하지만, Kafka·RabbitMQ 등 외부 브로커를 도입할 때는 어댑터 계층을 중심으로 교체할 수 있도록 분리했습니다.

## 스케줄 릴레이

Quartz, Redis, EventBridge 등 다양한 스케줄링 시스템과 연동하기 위해서는 실제 발송이 스케줄링 서비스에 결합된 형태로 구현하는 것이 아니라, 스케줄링 서비스는 단지 실행 시점을 알려주는 역할만
하는 것이 좋다고 판단하여 릴레이 계층을 추가하게 되었습니다.

현재 스케줄 릴레이의 경우는 DB 기반으로 구현하였고 `ScheduledNotificationJob`을 사용하여 DB에 스케줄을 등록하고 `@Scheduled`로 주기적으로 실행되는 구조로 구현하였습니다.

```java
// DbScheduledNotificationJobRelay — SCHEDULED → PROCESSING 전이 담당
@Scheduled(fixedDelayString = "${notification.relay.schedule.fixed-delay-ms:10000}")
public void relay() {
    // 실행 시점이 도달한 ScheduledNotificationJob 조회
    List<ScheduledNotificationJob> readyJobs = scheduleRepository
        .findByTypeAndExecutedFalseAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            ScheduleType.INITIAL, OffsetDateTime.now(ZoneOffset.UTC), PageRequest.of(0, batchSize));

    for (ScheduledNotificationJob scheduled : readyJobs) {
        // 개별 Job 재조회 후 SCHEDULED → PROCESSING 전이
        scheduled.markExecuted();
        job.markProcessing();
        // 상태 변경 이벤트만 발행 — 실제 발송은 다음 단계에서
        eventPublisher.publish(new NotificationJobStatusChangedEvent(...));
    }
}
```

`ScheduledNotificationJob`는 현재는 스케줄 서비스를 대체하는 역할로 사용되고 있지만 추후 외부 스케줄링 시스템으로 대체할 때는, 외부 시스템의 요청/수행 기록을 관리하는 방향으로 역할이 전환될
수 있도록 설계했습니다.

## 실행 스케줄러

알림 발송 실행 스케줄러는 `PROCESSING` 상태의 `NotificationJob`을 찾아서 `NotificationJobExecutionEvent`를 발행하는 역할을 합니다.

```java
// ProcessingNotificationJobExecutionScheduler
@Scheduled(fixedDelayString = "${notification.relay.execution.fixed-delay-ms:5000}")
public void schedule() {
    List<NotificationJob> processingJobs = jobRepository.findByStatusAndDeletedFalse(
            JobStatus.PROCESSING, PageRequest.of(0, batchSize));

    for (NotificationJob job : processingJobs) {
        eventPublisher.publish(new NotificationJobExecutionEvent(
                job.getId(), job.getIdempotencyKey(), "Processing job execution relay"));
    }
}
```

실행 핸들러(`NotificationJobExecutionProcessor`)는 분산 락을 획득한 뒤 실제 발송을 수행합니다. 발송 시간이 길어질 경우 락 TTL 만료로 다른 인스턴스가 동일 Job을 처리할 수 있어, **Watchdog**이 `TTL / 3` 주기로 락을 자동 갱신합니다.

```java
// NotificationJobExecutionProcessor — 실행 핸들러 (간략)
public void process(NotificationJobExecutionEvent event) {
    Optional<String> lockToken = distributedLock.tryLock(event.idempotencyKey(), idempotencyTtl);
    if (lockToken.isEmpty()) { return; }  // 중복 이벤트 skip

    ScheduledFuture<?> watchdog = startWatchdog(event.idempotencyKey(), lockToken.get());
    try {
        sendOrchestrator.execute(job, "system:execution-handler");
    } finally {
        watchdog.cancel(false);
        distributedLock.unlock(event.idempotencyKey(), lockToken.get());
    }
}
```

알림 발송은 동기 호출 대신, `PROCESSING` 상태를 기준으로 실행 스케줄러가 후속 이벤트를 발행하는 구조로 설계했습니다. 이 방식은 발송 처리, retry, stuck recovery 같은 후속 시나리오를 분리하기 쉽다는 장점이 있습니다.

향후에는 CDC와 같은 방식으로 `PROCESSING` 전이를 감지해 `NotificationJobExecutionEvent`를 발행하는 구조로도 확장할 수 있습니다. 현재 문서에서는 **현재 구현 흐름**과 **확장 가능한 방향**을 구분해 설명합니다.