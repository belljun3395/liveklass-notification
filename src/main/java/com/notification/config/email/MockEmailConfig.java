package com.notification.config.email;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * MockEmailSender 관련 빈을 활성화하는 설정 클래스.
 *
 * <p>{@code @EnableConfigurationProperties} 를 통해 {@link MockEmailSenderControl} 을 Spring 컨테이너에 빈으로
 * 등록합니다. {@code MockEmailSenderControl} 은 {@code @ConfigurationProperties} 만 선언되어 있어 이 설정 없이는 빈으로
 * 등록되지 않습니다.
 *
 * <p>이 설정 클래스는 application.yml 의 {@code mock.email.*} 속성과 {@link MockEmailSenderControl} 을 연결하는 진입점
 * 역할을 합니다.
 */
@Profile("!prod")
@Configuration
@EnableConfigurationProperties(MockEmailSenderControl.class)
public class MockEmailConfig {}
