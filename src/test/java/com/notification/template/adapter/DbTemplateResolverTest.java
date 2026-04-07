package com.notification.template.adapter;

import static java.util.Locale.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.notification.domain.NotificationChannel;
import com.notification.support.AbstractIntegrationTest;
import com.notification.template.domain.NotificationTemplate;
import com.notification.template.exception.TemplateNotFoundException;
import com.notification.template.repository.NotificationTemplateRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("DbTemplateResolver")
@Transactional
class DbTemplateResolverTest extends AbstractIntegrationTest {

    @Autowired private DbTemplateResolver dbTemplateResolver;
    @Autowired private NotificationTemplateRepository templateRepository;

    @Test
    @DisplayName("신규 가입자 이메일 템플릿 조회")
    void shouldResolveNewUserWelcomeEmailTemplate() {
        templateRepository.save(
                NotificationTemplate.create(
                        "NEW_USER_WELCOME",
                        NotificationChannel.EMAIL,
                        KOREAN,
                        1,
                        "{{userName}}님을 환영합니다",
                        "{{userName}}님, LiveKlass에 가입해주셔서 감사합니다. 이메일 인증을 완료해주세요.",
                        null,
                        List.of()));

        var resolved =
                dbTemplateResolver.resolve("NEW_USER_WELCOME", NotificationChannel.EMAIL, "ko");

        assertThat(resolved.titleTemplate()).isEqualTo("{{userName}}님을 환영합니다");
        assertThat(resolved.contentTemplate())
                .isEqualTo("{{userName}}님, LiveKlass에 가입해주셔서 감사합니다. 이메일 인증을 완료해주세요.");
    }

    @Test
    @DisplayName("삭제된 템플릿은 조회되지 않는다")
    void deletedTemplatesShouldNotBeFound() {
        var template =
                NotificationTemplate.create(
                        "DELETED_TEMPLATE",
                        NotificationChannel.EMAIL,
                        KOREAN,
                        1,
                        "제목",
                        "본문",
                        null,
                        List.of());
        template.softDelete();
        templateRepository.save(template);

        assertThatThrownBy(
                        () ->
                                dbTemplateResolver.resolve(
                                        "DELETED_TEMPLATE", NotificationChannel.EMAIL, "ko"))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    @Test
    @DisplayName("존재하지 않는 템플릿 조회 시 예외가 발생한다")
    void shouldThrowExceptionForNonExistentTemplate() {
        assertThatThrownBy(
                        () ->
                                dbTemplateResolver.resolve(
                                        "NON_EXISTENT", NotificationChannel.EMAIL, "ko"))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    @Test
    @DisplayName("강좌 등록 템플릿 다중 버전 관리")
    void shouldReturnLatestCourseRegistrationTemplate() {
        // v1: 기본 강좌 등록 메시지
        templateRepository.save(
                NotificationTemplate.create(
                        "COURSE_REGISTRATION",
                        NotificationChannel.EMAIL,
                        KOREAN,
                        1,
                        "{{courseTitle}} 등록 완료",
                        "{{userName}}님이 {{courseTitle}} 강좌에 등록되었습니다.",
                        null,
                        List.of()));

        // v2: 개선된 강좌 등록 메시지 (강사명 추가)
        templateRepository.save(
                NotificationTemplate.create(
                        "COURSE_REGISTRATION",
                        NotificationChannel.EMAIL,
                        KOREAN,
                        2,
                        "{{courseTitle}} 강좌 등록을 환영합니다",
                        "{{userName}}님이 {{instructorName}} 강사의 {{courseTitle}} 강좌에 등록되었습니다.",
                        null,
                        List.of()));

        var resolved =
                dbTemplateResolver.resolve("COURSE_REGISTRATION", NotificationChannel.EMAIL, "ko");

        assertThat(resolved.titleTemplate()).isEqualTo("{{courseTitle}} 강좌 등록을 환영합니다");
        assertThat(resolved.contentTemplate())
                .isEqualTo("{{userName}}님이 {{instructorName}} 강사의 {{courseTitle}} 강좌에 등록되었습니다.");
    }
}
