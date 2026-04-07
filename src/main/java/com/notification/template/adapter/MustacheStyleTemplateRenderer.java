package com.notification.template.adapter;

import com.notification.notification.port.TemplateRenderer;
import com.notification.template.util.TemplateVariableExtractor;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MustacheStyleTemplateRenderer implements TemplateRenderer {

    @Override
    public String render(String template, Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return TemplateVariableExtractor.replace(template, key -> "{{" + key + "}}");
        }
        return TemplateVariableExtractor.replace(
                template, key -> variables.getOrDefault(key, "{{" + key + "}}"));
    }
}
