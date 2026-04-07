package com.notification.infra.util.id;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.hibernate.annotations.IdGeneratorType;

/**
 * TSID(Time-Sorted ID) 기반 PK 생성 애노테이션.
 *
 * <p>이 애노테이션이 붙은 {@code Long} 필드에 Hibernate가 엔티티 INSERT 시점에 {@link TsidIdentifierGenerator}를 통해
 * TSID 값을 자동 할당합니다.
 *
 * <p><b>TSID 구조 (64비트 Long):</b>
 *
 * <pre>
 *   [42비트 타임스탬프] [10비트 노드ID] [12비트 시퀀스]
 * </pre>
 *
 * 타임스탬프가 상위 비트에 있으므로 삽입 순서대로 정렬됩니다(시간순 정렬 보장).
 *
 * <p><b>주의 — JavaScript 정밀도 손실:</b><br>
 * TSID는 최대 {@code ~2^60} 범위로 JavaScript의 안전 정수 범위({@code Number.MAX_SAFE_INTEGER = 2^53 - 1})를
 * 초과합니다. JSON 응답에서 숫자로 내려보내면 JS 클라이언트에서 하위 비트가 소실됩니다. TSID를 포함하는 응답 DTO의 {@code Long} 필드에는 반드시
 * {@code @JsonSerialize(using = ToStringSerializer.class)}를 붙여 문자열로 직렬화하세요.
 */
@IdGeneratorType(TsidIdentifierGenerator.class)
@Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TsidId {}
