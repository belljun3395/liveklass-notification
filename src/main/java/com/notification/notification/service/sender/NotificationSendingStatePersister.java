package com.notification.notification.service.sender;

import com.notification.notification.domain.Notification;
import com.notification.notification.repository.notification.NotificationRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발송 전 {@code SENDING} 상태를 독립 트랜잭션으로 영속화합니다.
 *
 * <p>호출 측 트랜잭션({@code @ApplicationModuleListener}의 REQUIRES_NEW)과 별도로 커밋되므로, 발송 중 프로세스 크래시가 발생해도
 * {@code SENDING} 상태가 DB에 남아 {@link
 * com.notification.notification.event.schedule.StuckProcessingRecoveryScheduler}가 감지하고 복구할 수 있습니다.
 */
@Component
public class NotificationSendingStatePersister {

    private final NotificationRepository notificationRepository;

    public NotificationSendingStatePersister(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(List<Notification> targets) {
        notificationRepository.saveAll(targets);
    }
}
