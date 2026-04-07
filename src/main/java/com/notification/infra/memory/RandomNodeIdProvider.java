package com.notification.infra.memory;

import com.notification.infra.port.NodeIdProvider;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class RandomNodeIdProvider implements NodeIdProvider {

    private final int nodeId = ThreadLocalRandom.current().nextInt(1024);

    @Override
    public int getNodeId() {
        return nodeId;
    }
}
