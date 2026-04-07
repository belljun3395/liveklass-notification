package com.notification.template.exception;

import com.notification.support.web.exception.BadRequestException;
import java.util.List;

public class TemplateValidationException extends BadRequestException {

    private final List<String> errors;

    public TemplateValidationException(List<String> errors) {
        super("템플릿 변수 검증 실패: " + String.join(", ", errors));
        this.errors = errors;
    }

    public List<String> getErrors() {
        return errors;
    }
}
