package com.notification.template.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.template.domain.TemplateVariable;
import com.notification.template.domain.VariableDataType;
import com.notification.template.exception.InvalidTemplateException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TemplateVariableValidator")
class TemplateVariableValidatorTest {

    private static final TemplateVariable REQUIRED_USER_NAME =
            new TemplateVariable("userName", VariableDataType.STRING, true, "김철수", "사용자 이름");
    private static final TemplateVariable REQUIRED_EMAIL =
            new TemplateVariable(
                    "email", VariableDataType.STRING, true, "user@example.com", "사용자 이메일");
    private static final TemplateVariable OPTIONAL_COURSE_TITLE =
            new TemplateVariable(
                    "courseTitle", VariableDataType.STRING, false, "Spring Boot 실전", "강좌명");
    private static final TemplateVariable OPTIONAL_INSTRUCTOR_NAME =
            new TemplateVariable("instructorName", VariableDataType.STRING, false, "박강사", "강사명");

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("강좌 등록 템플릿에서 필수 변수 누락 시 경고 발생")
        void shouldWarnWhenCourseRegistrationMissingUserEmail() {
            var warnings =
                    TemplateVariableValidator.validate(
                            "{{userName}}님이 강좌에 등록되었습니다.",
                            "강좌 등록 완료",
                            List.of(REQUIRED_USER_NAME, REQUIRED_EMAIL),
                            Map.of("userName", "김철수"));

            assertThat(warnings).containsExactly("필수 변수 누락: email");
        }

        @Test
        @DisplayName("변수 데이터가 없으면 모든 필수 변수에 대해 경고 발생")
        void shouldWarnAllRequiredVariablesWhenDataIsNull() {
            var warnings =
                    TemplateVariableValidator.validate(
                            "{{userName}}님, {{courseTitle}} 수강을 시작하세요.",
                            "강좌 시작 안내",
                            List.of(REQUIRED_USER_NAME, REQUIRED_EMAIL, OPTIONAL_COURSE_TITLE),
                            null);

            assertThat(warnings).containsExactlyInAnyOrder("필수 변수 누락: userName", "필수 변수 누락: email");
        }

        @Test
        @DisplayName("모든 필수 변수가 제공되면 누락 경고 없음")
        void shouldHaveNoMissingWarningWhenAllRequiredVariablesProvided() {
            var warnings =
                    TemplateVariableValidator.validate(
                            "{{userName}}님께서 강좌를 구매하셨습니다.",
                            "강좌 구매 감사",
                            List.of(REQUIRED_USER_NAME, REQUIRED_EMAIL),
                            Map.of("userName", "김철수", "email", "kim@example.com"));

            assertThat(warnings).isEmpty();
        }

        @Test
        @DisplayName("선언되지 않은 변수 사용 시 경고 발생")
        void shouldWarnWhenUsingUndeclaredVariable() {
            var warnings =
                    TemplateVariableValidator.validate(
                            "{{userName}}님, {{promotionCode}}로 {{discount}}% 할인 받으세요.",
                            "특별 할인 오퍼",
                            List.of(REQUIRED_USER_NAME, REQUIRED_EMAIL),
                            Map.of("userName", "김철수", "email", "kim@example.com"));

            assertThat(warnings)
                    .containsExactlyInAnyOrder(
                            "스키마에 미선언 변수: promotionCode", "스키마에 미선언 변수: discount");
        }

        @Test
        @DisplayName("유효한 강좌 알림 템플릿은 모든 검증 통과")
        void shouldPassAllValidationsForValidCourseNotification() {
            var warnings =
                    TemplateVariableValidator.validate(
                            "{{userName}}님이 {{instructorName}} 강사의 {{courseTitle}}를 수강 중입니다.",
                            "{{userName}}님 환영합니다",
                            List.of(
                                    REQUIRED_USER_NAME,
                                    REQUIRED_EMAIL,
                                    OPTIONAL_COURSE_TITLE,
                                    OPTIONAL_INSTRUCTOR_NAME),
                            Map.of(
                                    "userName", "김철수",
                                    "email", "kim@example.com",
                                    "courseTitle", "Spring Boot 실전",
                                    "instructorName", "박강사"));

            assertThat(warnings).isEmpty();
        }

        @Test
        @DisplayName("bodyTemplate이 null이면 예외가 발생한다")
        void shouldThrowExceptionWhenBodyTemplateIsNull() {
            assertThatThrownBy(
                            () ->
                                    TemplateVariableValidator.validate(
                                            null,
                                            "이메일 인증 필요",
                                            List.of(REQUIRED_USER_NAME, REQUIRED_EMAIL),
                                            Map.of("userName", "김철수", "email", "kim@example.com")))
                    .isInstanceOf(InvalidTemplateException.class);
        }

        @Test
        @DisplayName("titleTemplate이 null이면 예외가 발생한다")
        void shouldThrowExceptionWhenTitleTemplateIsNull() {
            assertThatThrownBy(
                            () ->
                                    TemplateVariableValidator.validate(
                                            "{{userName}}님, 이메일 인증을 완료해주세요.",
                                            null,
                                            List.of(REQUIRED_USER_NAME, REQUIRED_EMAIL),
                                            Map.of("userName", "김철수", "email", "kim@example.com")))
                    .isInstanceOf(InvalidTemplateException.class);
        }
    }

    @Nested
    @DisplayName("validateSchemaOnly")
    class ValidateSchemaOnly {

        @Test
        @DisplayName("스키마에 선언되지 않은 변수 사용 시 경고 발생")
        void shouldWarnWhenUsingUndeclaredVariableInTemplate() {
            var warnings =
                    TemplateVariableValidator.validateSchemaOnly(
                            "{{userName}}님께 {{discountCode}}를 발급했습니다.",
                            "할인 코드 발급",
                            List.of(REQUIRED_USER_NAME, REQUIRED_EMAIL));

            assertThat(warnings).containsExactly("스키마에 미선언 변수: discountCode");
        }

        @Test
        @DisplayName("모든 변수가 스키마에 정의되면 경고 없음")
        void shouldHaveNoWarningsWhenAllVariablesAreDeclared() {
            var warnings =
                    TemplateVariableValidator.validateSchemaOnly(
                            "{{instructorName}} 강사의 {{courseTitle}}에 등록되었습니다.",
                            "{{userName}}님을 환영합니다.",
                            List.of(
                                    REQUIRED_USER_NAME,
                                    OPTIONAL_COURSE_TITLE,
                                    OPTIONAL_INSTRUCTOR_NAME));

            assertThat(warnings).isEmpty();
        }

        @Test
        @DisplayName("제목과 본문의 모든 변수를 함께 검증")
        void shouldValidateVariablesFromBothTitleAndBody() {
            var warnings =
                    TemplateVariableValidator.validateSchemaOnly(
                            "{{courseTitle}} 강좌 시작",
                            "{{userName}}님, {{courseTitle}} 강좌가 시작되었습니다. 강사: {{unknownInstructor}}",
                            List.of(REQUIRED_USER_NAME, OPTIONAL_COURSE_TITLE));

            assertThat(warnings).containsExactly("스키마에 미선언 변수: unknownInstructor");
        }

        @Test
        @DisplayName("bodyTemplate이 null이면 예외가 발생한다")
        void shouldThrowExceptionWhenBodyIsNullInSchemaOnly() {
            assertThatThrownBy(
                            () ->
                                    TemplateVariableValidator.validateSchemaOnly(
                                            null,
                                            "{{userName}}님 강좌 등록 완료",
                                            List.of(REQUIRED_USER_NAME, REQUIRED_EMAIL)))
                    .isInstanceOf(InvalidTemplateException.class);
        }

        @Test
        @DisplayName("titleTemplate이 null이면 예외가 발생한다")
        void shouldThrowExceptionWhenTitleIsNullInSchemaOnly() {
            assertThatThrownBy(
                            () ->
                                    TemplateVariableValidator.validateSchemaOnly(
                                            "{{userName}}님이 강좌에 등록되었습니다.",
                                            null,
                                            List.of(REQUIRED_USER_NAME, REQUIRED_EMAIL)))
                    .isInstanceOf(InvalidTemplateException.class);
        }
    }
}
