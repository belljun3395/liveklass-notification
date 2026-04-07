package com.notification.template.domain;

import com.notification.infra.util.id.TsidId;
import com.notification.notification.domain.NotificationChannel;
import com.notification.template.domain.converter.TemplateVariableListConverter;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(
        name = "notification_templates",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_template_code_channel_locale_version",
                        columnNames = {"code", "channel", "locale", "version"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationTemplate {

    @TsidId @Id private Long id;

    @Column(nullable = false, length = 100)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(nullable = false, length = 10)
    private String locale;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, name = "title_template", length = 500)
    private String titleTemplate;

    @Column(nullable = false, name = "body_template", columnDefinition = "TEXT")
    private String bodyTemplate;

    @Column(length = 500)
    private String description;

    @Convert(converter = TemplateVariableListConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private List<TemplateVariable> variables;

    @Column(nullable = false)
    private boolean deleted;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public void softDelete() {
        this.deleted = true;
    }

    public static NotificationTemplate create(
            String code,
            NotificationChannel channel,
            Locale locale,
            int version,
            String titleTemplate,
            String bodyTemplate,
            String description,
            List<TemplateVariable> variables) {
        NotificationTemplate t = new NotificationTemplate();
        t.code = code;
        t.channel = channel;
        t.locale = locale.toLanguageTag();
        t.version = version;
        t.titleTemplate = titleTemplate;
        t.bodyTemplate = bodyTemplate;
        t.description = description;
        t.variables = variables != null ? variables : List.of();
        t.deleted = false;
        return t;
    }
}
