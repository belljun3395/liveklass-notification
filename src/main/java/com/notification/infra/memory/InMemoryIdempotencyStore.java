package com.notification.infra.memory;

import com.notification.infra.port.IdempotencyStore;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * ConcurrentHashMap 기반 단일 인스턴스 멱등성 저장소 구현체입니다.
 *
 * <p>운영 환경에서는 Redis 구현체로 교체합니다. 모든 연산에 1~3ms 랜덤 지연을 추가하여 Redis 네트워크 레이턴시를 시뮬레이션합니다.
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Set<String> issuedKeys = ConcurrentHashMap.newKeySet();

    @Override
    public String generateKey() {
        simulateNetworkLatency();
        String key = UUID.randomUUID().toString();
        issuedKeys.add(key);
        return key;
    }

    @Override
    public boolean isIssued(String key) {
        simulateNetworkLatency();
        return issuedKeys.contains(key);
    }

    private void simulateNetworkLatency() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 4));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
