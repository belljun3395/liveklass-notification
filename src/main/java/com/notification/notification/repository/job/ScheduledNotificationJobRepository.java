package com.notification.notification.repository.job;

import com.notification.notification.domain.ScheduleType;
import com.notification.notification.domain.ScheduledNotificationJob;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

public interface ScheduledNotificationJobRepository
        extends JpaRepository<ScheduledNotificationJob, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    List<ScheduledNotificationJob>
            findByTypeAndExecutedFalseAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                    ScheduleType type, OffsetDateTime scheduledAt, Pageable pageable);

    List<ScheduledNotificationJob> findAllByJobIdOrderByScheduledAtAsc(Long jobId);

    List<ScheduledNotificationJob> findByJobIdAndExecutedFalse(Long jobId);

    Optional<ScheduledNotificationJob> findByJobIdAndType(Long jobId, ScheduleType type);
}
