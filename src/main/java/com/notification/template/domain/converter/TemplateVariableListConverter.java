package com.notification.template.domain.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.template.domain.TemplateVariable;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class TemplateVariableListConverter
        implements AttributeConverter<List<TemplateVariable>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<TemplateVariable>> TYPE_REF = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<TemplateVariable> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize template variables", e);
        }
    }

    @Override
    public List<TemplateVariable> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(dbData, TYPE_REF);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize template variables", e);
        }
    }
}
