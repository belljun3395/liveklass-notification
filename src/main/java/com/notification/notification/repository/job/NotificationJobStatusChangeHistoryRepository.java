package com.notification.notification.repository.job;

import com.notification.notification.domain.NotificationJobStatusChangeHistory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationJobStatusChangeHistoryRepository
        extends JpaRepository<NotificationJobStatusChangeHistory, Long> {

    Optional<NotificationJobStatusChangeHistory> findTopByJobIdOrderByCreatedAtDesc(Long jobId);
}
