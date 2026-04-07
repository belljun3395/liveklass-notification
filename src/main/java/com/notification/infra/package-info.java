/**
 * 기술적 공통 유틸리티 모듈 — 모든 비즈니스 모듈이 자유롭게 사용할 수 있는 공개 모듈.
 *
 * <p>비즈니스 도메인과 무관한 인프라 기술 수준의 공통 기능을 포트(인터페이스)와 기본 구현체 형태로 제공합니다.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.notification.infra;
