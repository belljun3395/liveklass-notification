package com.notification.infra.memory;

import com.notification.infra.port.DistributedLock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * ConcurrentHashMap 기반 단일 인스턴스 분산 락 구현체입니다.
 *
 * <p>운영 환경에서는 Redis 구현체({@code SET key token NX PX ttl})로 교체합니다. 모든 락 연산에 1~3ms 랜덤 지연을 추가하여 Redis
 * 네트워크 레이턴시를 시뮬레이션합니다.
 */
@Component
public class InMemoryDistributedLock implements DistributedLock {

    private record LockInfo(String ownerToken, long expiresAt) {}

    private final ConcurrentHashMap<String, LockInfo> locks = new ConcurrentHashMap<>();

    @Override
    public Optional<String> tryLock(String lockName, Duration ttl) {
        simulateNetworkLatency();
        long now = System.currentTimeMillis();
        String token = UUID.randomUUID().toString();
        LockInfo newLock = new LockInfo(token, now + ttl.toMillis());

        LockInfo existing = locks.putIfAbsent(lockName, newLock);
        if (existing == null) {
            return Optional.of(token);
        }
        if (now > existing.expiresAt()) {
            return locks.replace(lockName, existing, newLock)
                    ? Optional.of(token)
                    : Optional.empty();
        }
        return Optional.empty();
    }

    @Override
    public boolean renew(String lockName, String ownerToken, Duration ttl) {
        simulateNetworkLatency();
        long now = System.currentTimeMillis();
        LockInfo existing = locks.get(lockName);
        if (existing == null
                || now > existing.expiresAt()
                || !existing.ownerToken().equals(ownerToken)) {
            return false;
        }
        return locks.replace(lockName, existing, new LockInfo(ownerToken, now + ttl.toMillis()));
    }

    @Override
    public void unlock(String lockName, String ownerToken) {
        simulateNetworkLatency();
        locks.computeIfPresent(
                lockName, (key, info) -> info.ownerToken().equals(ownerToken) ? null : info);
    }

    private void simulateNetworkLatency() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 4));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
