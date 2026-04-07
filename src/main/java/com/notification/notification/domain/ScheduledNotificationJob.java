package com.notification.notification.domain;

import com.notification.infra.util.id.TsidId;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "scheduled_notification_jobs")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduledNotificationJob {

    @TsidId @Id private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 20)
    private ScheduleType type;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(nullable = false)
    private boolean executed;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public void markExecuted() {
        this.executed = true;
    }

    /** 관리자/시스템이 예약한 최초 발송 스케줄을 생성합니다. */
    public static ScheduledNotificationJob create(Long jobId, OffsetDateTime scheduledAt) {
        ScheduledNotificationJob s = new ScheduledNotificationJob();
        s.jobId = jobId;
        s.type = ScheduleType.INITIAL;
        s.scheduledAt = scheduledAt;
        s.executed = false;
        return s;
    }

    /** 실패한 알림의 재발송을 위해 생성하는 스케줄입니다. */
    public static ScheduledNotificationJob createRetry(Long jobId, OffsetDateTime retryAt) {
        ScheduledNotificationJob s = new ScheduledNotificationJob();
        s.jobId = jobId;
        s.type = ScheduleType.RETRY;
        s.scheduledAt = retryAt;
        s.executed = false;
        return s;
    }
}
