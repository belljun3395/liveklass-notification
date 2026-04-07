package com.notification.notification.service.job.send;

import com.notification.notification.domain.FailureClassification;
import com.notification.notification.domain.FailureReasonCode;
import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationChannel;
import java.util.List;

public interface NotificationJobSender {

    /** 알림 콘텐츠를 준비하고 채널로 발송합니다. 상태 전이 없이 발송 결과만 반환합니다. */
    SendResult send(JobSendContext context, List<Notification> targets);

    record JobSendContext(
            NotificationChannel channel, String titleTemplate, String contentTemplate) {}

    record SendResult(List<Notification> sent, List<FailedSend> failed) {
        public boolean hasFailures() {
            return !failed.isEmpty();
        }

        public List<Notification> failedNotifications() {
            return failed.stream().map(FailedSend::notification).toList();
        }
    }

    record FailedSend(
            Notification notification,
            FailureClassification classification,
            String reason,
            FailureReasonCode failureReasonCode) {}
}
