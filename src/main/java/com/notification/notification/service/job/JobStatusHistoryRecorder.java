package com.notification.notification.service.job;

import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.NotificationJobStatusChangeHistory;
import com.notification.notification.repository.job.NotificationJobStatusChangeHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JobStatusHistoryRecorder {

    private final NotificationJobStatusChangeHistoryRepository historyRepository;

    public JobStatusHistoryRecorder(
            NotificationJobStatusChangeHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    public void execute(
            Long jobId,
            JobStatus preStatus,
            JobStatus newStatus,
            String reason,
            String triggeredBy) {
        historyRepository.save(
                NotificationJobStatusChangeHistory.create(
                        jobId, preStatus, newStatus, reason, triggeredBy));
        log.debug(
                "Recorded job status change: job={}, {} -> {} (by {})",
                jobId,
                preStatus,
                newStatus,
                triggeredBy);
    }
}
