package com.notification.template.exception;

import com.notification.notification.domain.NotificationChannel;
import com.notification.support.web.exception.ResourceNotFoundException;

public class TemplateNotFoundException extends ResourceNotFoundException {

    public TemplateNotFoundException(Long templateId) {
        super("템플릿을 찾을 수 없습니다: " + templateId);
    }

    public TemplateNotFoundException(String groupCode, NotificationChannel channel, String locale) {
        super("활성 템플릿이 없습니다: code=" + groupCode + " channel=" + channel + " locale=" + locale);
    }
}
