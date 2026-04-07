package com.notification.notification.port;

import com.notification.notification.domain.NotificationChannel;

public interface TemplateResolver {

    ResolvedTemplate resolve(String templateCode, NotificationChannel channel, String locale);

    record ResolvedTemplate(String titleTemplate, String contentTemplate) {}
}
