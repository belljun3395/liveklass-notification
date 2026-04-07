package com.notification.template.application;

import com.notification.template.application.dto.TemplateUseCaseOut;
import com.notification.template.exception.TemplateNotFoundException;
import com.notification.template.repository.NotificationTemplateRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class GetTemplateUseCase {

    private final NotificationTemplateRepository templateRepository;

    public GetTemplateUseCase(NotificationTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Transactional(readOnly = true)
    public TemplateUseCaseOut execute(Long templateId) {
        return templateRepository
                .findByIdAndDeletedFalse(templateId)
                .map(TemplateUseCaseOut::from)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
    }
}
