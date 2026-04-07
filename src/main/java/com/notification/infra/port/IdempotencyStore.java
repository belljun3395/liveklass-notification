package com.notification.infra.port;

/** 멱등성 키 저장소. 고유한 키 생성 및 발급 이력 관리를 담당합니다. */
public interface IdempotencyStore {

    /**
     * 고유한 멱등성 키를 생성하고 발급 이력에 등록합니다.
     *
     * @return 새로 생성된 고유 키
     */
    String generateKey();

    /**
     * 키가 이 저장소에서 발급된 키인지 확인합니다.
     *
     * @param key 확인할 키
     * @return 이 저장소에서 발급된 키이면 {@code true}
     */
    boolean isIssued(String key);

    /**
     * 발급된 키를 저장소에서 삭제합니다. 인메모리 구현은 기본적으로 아무 동작도 하지 않으며, Redis 구현에서 재정의합니다.
     *
     * @param key 삭제할 키
     */
    default void delete(String key) {
        // no-op by default; Redis implementation overrides this
    }
}
