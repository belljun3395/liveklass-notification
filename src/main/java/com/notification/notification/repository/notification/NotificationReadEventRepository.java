package com.notification.notification.repository.notification;

import com.notification.notification.domain.NotificationReadEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationReadEventRepository
        extends JpaRepository<NotificationReadEvent, Long> {}
