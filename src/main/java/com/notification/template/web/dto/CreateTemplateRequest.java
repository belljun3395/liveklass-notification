package com.notification.template.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateTemplateRequest(
        @NotBlank String code,
        @NotNull String channel,
        String locale,
        @NotBlank String titleTemplate,
        @NotBlank String bodyTemplate,
        String description,
        @Valid List<Variable> variables) {

    public record Variable(
            @NotBlank String name,
            @NotNull String dataType,
            boolean required,
            String exampleValue,
            String description) {}
}
