package com.notification.infra.port;

import java.time.Duration;
import java.util.Optional;

/** 분산 락 인터페이스. 소유 토큰 기반으로 안전한 획득/갱신/해제를 보장합니다. */
public interface DistributedLock {

    /**
     * 락을 시도합니다. 성공 시 소유 토큰을 반환하며, 이미 유효한 락이 존재하면 빈 Optional을 반환합니다.
     *
     * @param lockName 락의 이름
     * @param ttl 만료 시간
     * @return 획득 성공 시 소유 토큰, 실패 시 empty
     */
    Optional<String> tryLock(String lockName, Duration ttl);

    /**
     * 락의 TTL을 갱신합니다. 소유 토큰이 일치하지 않거나 락이 만료된 경우 false를 반환합니다.
     *
     * @param lockName 락의 이름
     * @param ownerToken tryLock으로 획득한 소유 토큰
     * @param ttl 갱신할 만료 시간
     * @return 갱신 성공 여부
     */
    boolean renew(String lockName, String ownerToken, Duration ttl);

    /**
     * 락을 해제합니다. 소유 토큰이 일치하지 않는 경우 무시됩니다.
     *
     * @param lockName 락의 이름
     * @param ownerToken tryLock으로 획득한 소유 토큰
     */
    void unlock(String lockName, String ownerToken);
}
