package com.notification.notification.repository.job;

import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.NotificationJob;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface NotificationJobRepository extends JpaRepository<NotificationJob, Long> {

    Optional<NotificationJob> findByIdAndDeletedFalse(Long id);

    List<NotificationJob> findAllByIdInAndDeletedFalse(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM NotificationJob j WHERE j.id = :id AND j.deleted = false")
    Optional<NotificationJob> findByIdAndDeletedFalseForUpdate(@Param("id") Long id);

    Optional<NotificationJob> findByIdempotencyKeyAndDeletedFalse(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    List<NotificationJob> findByStatusAndDeletedFalseAndUpdatedAtBefore(
            JobStatus status, OffsetDateTime cutoff, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    List<NotificationJob> findByStatusAndDeletedFalse(JobStatus status, Pageable pageable);
}
