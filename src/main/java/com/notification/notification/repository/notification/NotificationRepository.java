package com.notification.notification.repository.notification;

import com.notification.notification.domain.Notification;
import com.notification.notification.domain.NotificationStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByIdAndDeletedFalse(Long id);

    List<Notification> findByRecipientIdAndIdLessThanAndDeletedFalseOrderByIdDesc(
            Long recipientId, Long cursorId, Pageable pageable);

    List<Notification>
            findByRecipientIdAndFirstReadAtIsNullAndIdLessThanAndDeletedFalseOrderByIdDesc(
                    Long recipientId, Long cursorId, Pageable pageable);

    List<Notification>
            findByRecipientIdAndFirstReadAtIsNotNullAndIdLessThanAndDeletedFalseOrderByIdDesc(
                    Long recipientId, Long cursorId, Pageable pageable);

    List<Notification> findAllByJobIdAndDeletedFalse(Long jobId);

    List<Notification> findByJobIdAndStatusAndDeletedFalse(Long jobId, NotificationStatus status);

    List<Notification> findByJobIdAndStatusInAndDeletedFalse(
            Long jobId, List<NotificationStatus> statuses);

    long countByJobIdAndDeletedFalse(Long jobId);

    long countByJobIdAndStatusAndDeletedFalse(Long jobId, NotificationStatus status);

    long countByJobIdAndStatusInAndDeletedFalse(Long jobId, List<NotificationStatus> statuses);

    long countByStatusAndDeletedFalse(NotificationStatus status);

    @Modifying
    @Query(
            value =
                    "UPDATE notifications SET first_read_at = COALESCE(LEAST(first_read_at, :readAt), :readAt)"
                            + " WHERE id = :id AND deleted = false",
            nativeQuery = true)
    int markFirstReadAt(@Param("id") Long id, @Param("readAt") OffsetDateTime readAt);
}
