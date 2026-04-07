package com.notification.notification.repository.notification;

import com.notification.notification.domain.NotificationContent;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationContentRepository extends JpaRepository<NotificationContent, Long> {
    Optional<NotificationContent> findByNotificationId(Long notificationId);

    List<NotificationContent> findAllByNotificationIdIn(Collection<Long> notificationIds);
}
