package com.notification.template.application.dto;

import com.notification.notification.domain.NotificationChannel;
import com.notification.template.domain.VariableDataType;
import java.util.List;

public record CreateTemplateUseCaseIn(
        String code,
        NotificationChannel channel,
        String locale,
        String titleTemplate,
        String bodyTemplate,
        String description,
        List<Variable> variables) {

    public record Variable(
            String name,
            VariableDataType dataType,
            boolean required,
            String exampleValue,
            String description) {}
}
