package com.notification.template.exception;

import com.notification.support.web.exception.BadRequestException;

public class InvalidTemplateException extends BadRequestException {

    public InvalidTemplateException(String message) {
        super(message);
    }
}
