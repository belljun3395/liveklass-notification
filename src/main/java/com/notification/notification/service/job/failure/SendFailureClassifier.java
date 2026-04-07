package com.notification.notification.service.job.failure;

import com.notification.notification.domain.Notification;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface SendFailureClassifier {

    /** 발송에 실패한 알림을 {@code DEAD_LETTER} 대상과 재시도 대상으로 분류합니다. */
    ClassificationResult classify(List<Notification> failed);

    record ClassificationResult(
            List<Notification> deadLetters,
            List<Notification> retryable,
            Optional<OffsetDateTime> nextRetryAt) {

        public boolean hasRetryable() {
            return !retryable.isEmpty();
        }
    }
}
