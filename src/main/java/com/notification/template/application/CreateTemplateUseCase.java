package com.notification.template.application;

import com.notification.template.application.dto.CreateTemplateUseCaseIn;
import com.notification.template.application.dto.TemplateUseCaseOut;
import com.notification.template.domain.NotificationTemplate;
import com.notification.template.domain.TemplateVariable;
import com.notification.template.exception.TemplateValidationException;
import com.notification.template.repository.NotificationTemplateRepository;
import com.notification.template.util.TemplateVariableValidator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CreateTemplateUseCase {

    private final NotificationTemplateRepository templateRepository;

    public CreateTemplateUseCase(NotificationTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Transactional
    public TemplateUseCaseOut execute(CreateTemplateUseCaseIn useCaseIn) {
        Locale locale =
                useCaseIn.locale() != null ? Locale.of(useCaseIn.locale()) : Locale.getDefault();

        int nextVersion =
                templateRepository.findMaxVersion(
                                useCaseIn.code(), useCaseIn.channel(), locale.toString())
                        + 1;

        List<TemplateVariable> variables =
                useCaseIn.variables() == null
                        ? List.of()
                        : useCaseIn.variables().stream()
                                .map(
                                        v ->
                                                new TemplateVariable(
                                                        v.name(),
                                                        v.dataType(),
                                                        v.required(),
                                                        v.exampleValue(),
                                                        v.description()))
                                .toList();

        List<String> schemaErrors =
                TemplateVariableValidator.validateSchemaOnly(
                        useCaseIn.bodyTemplate(), useCaseIn.titleTemplate(), variables);
        if (!schemaErrors.isEmpty()) {
            throw new TemplateValidationException(schemaErrors);
        }

        NotificationTemplate template =
                NotificationTemplate.create(
                        useCaseIn.code(),
                        useCaseIn.channel(),
                        locale,
                        nextVersion,
                        useCaseIn.titleTemplate(),
                        useCaseIn.bodyTemplate(),
                        useCaseIn.description(),
                        variables);
        templateRepository.save(template);

        return TemplateUseCaseOut.from(template);
    }
}
