package com.notification.template.util;

import com.notification.template.domain.TemplateVariable;
import com.notification.template.exception.InvalidTemplateException;
import java.util.*;
import java.util.stream.Collectors;
import lombok.experimental.*;

@UtilityClass
public class TemplateVariableValidator {

    public static List<String> validate(
            String bodyTemplate,
            String titleTemplate,
            List<TemplateVariable> schema,
            Map<String, String> variables) {
        if (bodyTemplate == null || titleTemplate == null) {
            throw new InvalidTemplateException("bodyTemplate과 titleTemplate은 null일 수 없습니다");
        }

        List<String> warnings = new ArrayList<>();

        Set<String> usedInTemplate = collectUsedNames(bodyTemplate, titleTemplate);
        Set<String> schemaNames =
                schema.stream().map(TemplateVariable::name).collect(Collectors.toSet());

        for (TemplateVariable v : schema) {
            if (v.required() && (variables == null || !variables.containsKey(v.name()))) {
                warnings.add("필수 변수 누락: " + v.name());
            }
        }

        for (String used : usedInTemplate) {
            if (!schemaNames.contains(used)) {
                warnings.add("스키마에 미선언 변수: " + used);
            }
        }

        return warnings;
    }

    public static List<String> validateSchemaOnly(
            String bodyTemplate, String titleTemplate, List<TemplateVariable> schema) {
        if (bodyTemplate == null || titleTemplate == null) {
            throw new InvalidTemplateException("bodyTemplate과 titleTemplate은 null일 수 없습니다");
        }

        Set<String> usedInTemplate = collectUsedNames(bodyTemplate, titleTemplate);
        Set<String> schemaNames =
                schema.stream().map(TemplateVariable::name).collect(Collectors.toSet());

        return usedInTemplate.stream()
                .filter(v -> !schemaNames.contains(v))
                .map(v -> "스키마에 미선언 변수: " + v)
                .toList();
    }

    private Set<String> collectUsedNames(String bodyTemplate, String titleTemplate) {
        Set<String> result = new HashSet<>();
        result.addAll(TemplateVariableExtractor.extractNames(bodyTemplate));
        result.addAll(TemplateVariableExtractor.extractNames(titleTemplate));
        return result;
    }
}
