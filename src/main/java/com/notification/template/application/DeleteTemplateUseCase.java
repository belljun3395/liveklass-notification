package com.notification.template.application;

import com.notification.template.exception.TemplateNotFoundException;
import com.notification.template.repository.NotificationTemplateRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DeleteTemplateUseCase {

    private final NotificationTemplateRepository templateRepository;

    public DeleteTemplateUseCase(NotificationTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Transactional
    public void execute(Long templateId) {
        var template =
                templateRepository
                        .findById(templateId)
                        .orElseThrow(() -> new TemplateNotFoundException(templateId));
        template.softDelete();
    }
}
