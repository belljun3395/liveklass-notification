package com.notification.template.application.dto;

import java.util.List;

public record PreviewTemplateUseCaseOut(
        String renderedTitle, String renderedBody, List<String> warnings) {}
