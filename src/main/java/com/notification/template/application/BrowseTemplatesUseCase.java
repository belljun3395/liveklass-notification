package com.notification.template.application;

import com.notification.template.application.dto.BrowseTemplatesUseCaseIn;
import com.notification.template.application.dto.TemplateUseCaseOut;
import com.notification.template.repository.NotificationTemplateRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BrowseTemplatesUseCase {

    private final NotificationTemplateRepository templateRepository;

    public BrowseTemplatesUseCase(NotificationTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Transactional(readOnly = true)
    public List<TemplateUseCaseOut> execute(BrowseTemplatesUseCaseIn useCaseIn) {
        return templateRepository
                .findByCodeAndDeletedFalseOrderByVersionDesc(useCaseIn.code())
                .stream()
                .map(TemplateUseCaseOut::from)
                .toList();
    }
}
