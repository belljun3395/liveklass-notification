package com.notification.config.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@Configuration
public class WebConfig {

    /**
     * Computes ETag from response body hash for template endpoints. Combined with Cache-Control:
     * max-age, this enables conditional requests after TTL expiry — clients send If-None-Match and
     * receive 304 if unchanged.
     */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> shallowEtagHeaderFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
                new FilterRegistrationBean<>();
        registration.setFilter(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/api/templates/*", "/api/templates");
        registration.setName("shallowEtagHeaderFilter");
        return registration;
    }
}
