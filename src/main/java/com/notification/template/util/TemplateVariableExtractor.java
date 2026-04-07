package com.notification.template.util;

import java.util.HashSet;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.*;

@UtilityClass
public class TemplateVariableExtractor {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    public static Set<String> extractNames(String template) {
        Set<String> result = new HashSet<>();
        if (template == null) return result;
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /**
     * 템플릿 문자열에서 {{varName}} 패턴을 찾아 replacer 함수의 반환값으로 교체한다. 패턴 탐색 책임을 외부로 노출하지 않기 위해 이 메서드를 통해서만
     * 치환한다.
     */
    public static String replace(String template, UnaryOperator<String> replacer) {
        if (template == null) return "";
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacer.apply(key)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
