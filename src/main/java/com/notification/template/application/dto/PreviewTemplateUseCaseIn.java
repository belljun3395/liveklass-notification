package com.notification.template.application.dto;

import java.util.Map;

public record PreviewTemplateUseCaseIn(Long templateId, Map<String, String> variables) {}
