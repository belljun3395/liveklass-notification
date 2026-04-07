package com.notification.notification.port;

import java.util.Map;

public interface TemplateRenderer {

    String render(String template, Map<String, String> variables);
}
