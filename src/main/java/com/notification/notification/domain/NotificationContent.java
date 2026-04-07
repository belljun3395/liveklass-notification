package com.notification.notification.domain;

import com.notification.infra.util.id.TsidId;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notification_contents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationContent {

    @TsidId @Id private Long id;

    @Column(name = "notification_id", nullable = false, unique = true)
    private Long notificationId;

    @Column(name = "rendered_title", length = 500)
    private String renderedTitle;

    @Column(name = "rendered_body", nullable = false, columnDefinition = "TEXT")
    private String renderedBody;

    public static NotificationContent create(
            Long notificationId, String renderedTitle, String renderedBody) {
        NotificationContent c = new NotificationContent();
        c.notificationId = notificationId;
        c.renderedTitle = renderedTitle;
        c.renderedBody = renderedBody;
        return c;
    }
}
