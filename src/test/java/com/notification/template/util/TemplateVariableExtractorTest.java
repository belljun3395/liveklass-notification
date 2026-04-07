package com.notification.template.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TemplateVariableExtractor")
class TemplateVariableExtractorTest {

    @Nested
    @DisplayName("extractNames")
    class ExtractNames {

        @Test
        @DisplayName("null 템플릿이면 빈 Set을 반환한다")
        void shouldReturnEmptySetForNullTemplate() {
            assertThat(TemplateVariableExtractor.extractNames(null)).isEmpty();
        }

        @Test
        @DisplayName("변수가 없는 템플릿은 빈 Set을 반환한다")
        void shouldReturnEmptySetForTemplateWithoutVariables() {
            assertThat(TemplateVariableExtractor.extractNames("안녕하세요 홍길동님")).isEmpty();
        }

        @Test
        @DisplayName("단일 변수를 추출한다")
        void shouldExtractSingleVariable() {
            var names = TemplateVariableExtractor.extractNames("{{name}}님 환영합니다");
            assertThat(names).containsExactly("name");
        }

        @Test
        @DisplayName("여러 변수를 모두 추출한다")
        void shouldExtractMultipleVariables() {
            var names = TemplateVariableExtractor.extractNames("{{name}}님, {{course}} 강좌에 등록되었습니다");
            assertThat(names).containsExactlyInAnyOrder("name", "course");
        }

        @Test
        @DisplayName("중복 변수는 하나로 합쳐진다")
        void duplicateVariablesShouldBeMerged() {
            var names = TemplateVariableExtractor.extractNames("{{name}}님 안녕하세요, {{name}}님");
            assertThat(names).containsExactly("name");
        }
    }

    @Nested
    @DisplayName("replace")
    class Replace {

        @Test
        @DisplayName("null 템플릿은 빈 문자열을 반환한다")
        void shouldReturnEmptyStringForNullTemplate() {
            assertThat(TemplateVariableExtractor.replace(null, key -> "X")).isEmpty();
        }

        @Test
        @DisplayName("변수가 치환된다")
        void variablesShouldBeReplaced() {
            var result =
                    TemplateVariableExtractor.replace(
                            "{{name}}님, {{course}}에 등록되었습니다",
                            Map.of("name", "홍길동", "course", "Java 기초")::get);
            assertThat(result).isEqualTo("홍길동님, Java 기초에 등록되었습니다");
        }

        @Test
        @DisplayName("변수가 없는 템플릿은 원본을 그대로 반환한다")
        void templateWithoutVariablesReturnsOriginal() {
            var result = TemplateVariableExtractor.replace("안녕하세요", key -> key);
            assertThat(result).isEqualTo("안녕하세요");
        }

        @Test
        @DisplayName("replacer가 원본 플레이스홀더를 돌려주면 치환되지 않은 형태로 남는다")
        void shouldRemainUnchangedWhenReplacerReturnsOriginalPlaceholder() {
            var result =
                    TemplateVariableExtractor.replace(
                            "{{name}}님 {{course}} 등록", key -> "{{" + key + "}}");
            assertThat(result).isEqualTo("{{name}}님 {{course}} 등록");
        }
    }
}
