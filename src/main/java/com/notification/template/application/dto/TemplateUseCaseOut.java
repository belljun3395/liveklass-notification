package com.notification.template.application.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.notification.notification.domain.NotificationChannel;
import com.notification.template.domain.NotificationTemplate;
import com.notification.template.domain.TemplateVariable;
import java.time.OffsetDateTime;
import java.util.List;

public record TemplateUseCaseOut(
        // TSID(~2^60)는 JS Number.MAX_SAFE_INTEGER(2^53-1)를 초과하므로 문자열로 직렬화
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String code,
        NotificationChannel channel,
        String locale,
        int version,
        String titleTemplate,
        String bodyTemplate,
        String description,
        OffsetDateTime createdAt,
        List<VariableOut> variables) {

    public record VariableOut(
            String name,
            String dataType,
            boolean required,
            String exampleValue,
            String description) {

        public static VariableOut from(TemplateVariable v) {
            return new VariableOut(
                    v.name(), v.dataType().name(), v.required(), v.exampleValue(), v.description());
        }
    }

    public static TemplateUseCaseOut from(NotificationTemplate t) {
        return new TemplateUseCaseOut(
                t.getId(),
                t.getCode(),
                t.getChannel(),
                t.getLocale(),
                t.getVersion(),
                t.getTitleTemplate(),
                t.getBodyTemplate(),
                t.getDescription(),
                t.getCreatedAt(),
                t.getVariables().stream().map(VariableOut::from).toList());
    }
}
