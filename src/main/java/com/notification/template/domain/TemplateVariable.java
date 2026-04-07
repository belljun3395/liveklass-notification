package com.notification.template.domain;

public record TemplateVariable(
        String name,
        VariableDataType dataType,
        boolean required,
        String exampleValue,
        String description) {}
