package com.notification.template.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MustacheStyleTemplateRenderer")
class MustacheStyleTemplateRendererTest {

    private MustacheStyleTemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new MustacheStyleTemplateRenderer();
    }

    @Test
    @DisplayName("변수 맵이 null이면 플레이스홀더가 그대로 유지된다")
    void placeholdersRemainWhenVariableMapIsNull() {
        var result = renderer.render("{{name}}님 환영합니다", null);
        assertThat(result).isEqualTo("{{name}}님 환영합니다");
    }

    @Test
    @DisplayName("변수 맵이 비어있으면 플레이스홀더가 그대로 유지된다")
    void placeholdersRemainWhenVariableMapIsEmpty() {
        var result = renderer.render("{{name}}님 환영합니다", Map.of());
        assertThat(result).isEqualTo("{{name}}님 환영합니다");
    }

    @Test
    @DisplayName("변수가 치환된다")
    void variablesShouldBeSubstituted() {
        var result =
                renderer.render(
                        "{{name}}님, {{course}} 강좌에 등록되었습니다",
                        Map.of("name", "홍길동", "course", "Java 기초"));
        assertThat(result).isEqualTo("홍길동님, Java 기초 강좌에 등록되었습니다");
    }

    @Test
    @DisplayName("변수 맵에 없는 키는 플레이스홀더로 남는다")
    void missingKeysRemainAsPlaceholders() {
        var result = renderer.render("{{name}}님, {{course}} 등록", Map.of("name", "홍길동"));
        assertThat(result).isEqualTo("홍길동님, {{course}} 등록");
    }

    @Test
    @DisplayName("변수가 없는 템플릿은 원본을 그대로 반환한다")
    void templateWithoutVariablesReturnsOriginal() {
        var result = renderer.render("안녕하세요", Map.of("name", "홍길동"));
        assertThat(result).isEqualTo("안녕하세요");
    }
}
