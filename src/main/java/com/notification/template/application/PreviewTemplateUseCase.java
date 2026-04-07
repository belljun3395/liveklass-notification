package com.notification.template.application;

import com.notification.notification.port.TemplateRenderer;
import com.notification.template.application.dto.PreviewTemplateUseCaseIn;
import com.notification.template.application.dto.PreviewTemplateUseCaseOut;
import com.notification.template.exception.TemplateNotFoundException;
import com.notification.template.repository.NotificationTemplateRepository;
import com.notification.template.util.TemplateVariableValidator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PreviewTemplateUseCase {

    private final NotificationTemplateRepository templateRepository;
    private final TemplateRenderer templateRenderer;

    public PreviewTemplateUseCase(
            NotificationTemplateRepository templateRepository, TemplateRenderer templateRenderer) {
        this.templateRepository = templateRepository;
        this.templateRenderer = templateRenderer;
    }

    @Transactional(readOnly = true)
    public PreviewTemplateUseCaseOut execute(PreviewTemplateUseCaseIn useCaseIn) {
        var template =
                templateRepository
                        .findByIdAndDeletedFalse(useCaseIn.templateId())
                        .orElseThrow(() -> new TemplateNotFoundException(useCaseIn.templateId()));

        Map<String, String> vars = useCaseIn.variables() != null ? useCaseIn.variables() : Map.of();

        List<String> warnings =
                TemplateVariableValidator.validate(
                        template.getBodyTemplate(),
                        template.getTitleTemplate(),
                        template.getVariables(),
                        vars);

        String renderedTitle = templateRenderer.render(template.getTitleTemplate(), vars);
        String renderedBody = templateRenderer.render(template.getBodyTemplate(), vars);

        return new PreviewTemplateUseCaseOut(renderedTitle, renderedBody, warnings);
    }
}
