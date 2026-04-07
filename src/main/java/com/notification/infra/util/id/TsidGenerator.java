package com.notification.infra.util.id;

import com.notification.infra.port.NodeIdProvider;
import io.hypersistence.tsid.TSID;
import org.springframework.stereotype.Component;

/**
 * TSID 값을 생성하는 컴포넌트.
 *
 * <p>{@link NodeIdProvider}로부터 노드 ID를 주입받아 {@link TSID.Factory}를 초기화합니다. 노드 비트를 10비트로 설정하여 최대
 * 1,024개 노드까지 충돌 없이 분산 발급할 수 있습니다.
 *
 * <p>생성된 TSID는 {@code Long}으로 반환되며, 상위 42비트가 밀리초 타임스탬프이므로 삽입 순서와 PK 정렬 순서가 일치합니다.
 */
@Component
public class TsidGenerator {

    private final TSID.Factory factory;

    public TsidGenerator(NodeIdProvider nodeIdProvider) {
        this.factory =
                TSID.Factory.builder()
                        .withNodeBits(10)
                        .withNode(nodeIdProvider.getNodeId())
                        .build();
    }

    public long nextId() {
        return factory.generate().toLong();
    }
}
