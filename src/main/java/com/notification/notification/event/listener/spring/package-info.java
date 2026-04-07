/**
 * Spring Modulith(@ApplicationModuleListener) 소비 어댑터 모음.
 *
 * <p>각 클래스는 Spring Modulith 이벤트를 수신하여 processor로 위임하는 것 외에 아무것도 하지 않습니다. 브로커(Kafka 등)로 전환 시 이 패키지의
 * 클래스를 대체하고, processor는 그대로 재사용합니다.
 *
 * <pre>
 * event/listener/spring/   ← 이 패키지 (Spring Modulith 어댑터)
 * event/listener/kafka/    ← 브로커 전환 시 추가
 * event/processor/         ← 전송 방식 무관한 처리 로직
 * </pre>
 */
package com.notification.notification.event.listener.spring;
