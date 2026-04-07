package com.notification.template.adapter;

import com.notification.notification.domain.NotificationChannel;
import com.notification.notification.port.TemplateResolver;
import com.notification.template.exception.TemplateNotFoundException;
import com.notification.template.repository.NotificationTemplateRepository;
import org.springframework.stereotype.Component;

@Component
public class DbTemplateResolver implements TemplateResolver {

    private final NotificationTemplateRepository templateRepository;

    public DbTemplateResolver(NotificationTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Override
    public ResolvedTemplate resolve(
            String templateCode, NotificationChannel channel, String locale) {
        var template =
                templateRepository
                        .findTopByCodeAndChannelAndLocaleAndDeletedFalseOrderByVersionDesc(
                                templateCode, channel, locale)
                        .orElseThrow(
                                () -> new TemplateNotFoundException(templateCode, channel, locale));

        return new ResolvedTemplate(template.getTitleTemplate(), template.getBodyTemplate());
    }
}
