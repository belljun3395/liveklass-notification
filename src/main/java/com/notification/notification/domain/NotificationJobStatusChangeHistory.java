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
@Table(name = "notification_job_status_change_histories")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationJobStatusChangeHistory {

    @TsidId @Id private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobStatus preStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobStatus status;

    @Column(name = "status_change_reason", columnDefinition = "TEXT")
    private String statusChangeReason;

    @Column(name = "triggered_by")
    private String triggeredBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static NotificationJobStatusChangeHistory create(
            Long jobId,
            JobStatus preStatus,
            JobStatus status,
            String statusChangeReason,
            String triggeredBy) {
        NotificationJobStatusChangeHistory history = new NotificationJobStatusChangeHistory();
        history.jobId = jobId;
        history.preStatus = preStatus;
        history.status = status;
        history.statusChangeReason = statusChangeReason;
        history.triggeredBy = triggeredBy;
        return history;
    }
}
