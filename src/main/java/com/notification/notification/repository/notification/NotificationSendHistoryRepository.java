package com.notification.notification.repository.notification;

import com.notification.notification.domain.NotificationSendHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSendHistoryRepository
        extends JpaRepository<NotificationSendHistory, Long> {}
