package com.notification.infra.util.id;

import java.util.EnumSet;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;

/**
 * {@link TsidId} 애노테이션과 연결된 Hibernate PK 생성기.
 *
 * <p>Hibernate의 {@link BeforeExecutionGenerator}를 구현하여 INSERT 직전에 {@link TsidGenerator}로부터 TSID 값을
 * 받아 엔티티 ID로 할당합니다. {@link org.hibernate.generator.EventTypeSets#INSERT_ONLY}로 설정되어 있으므로 UPDATE 시에는
 * 호출되지 않습니다.
 */
public class TsidIdentifierGenerator implements BeforeExecutionGenerator {

    private final TsidGenerator tsidGenerator;

    public TsidIdentifierGenerator(TsidGenerator tsidGenerator) {
        this.tsidGenerator = tsidGenerator;
    }

    @Override
    public Object generate(
            SharedSessionContractImplementor session,
            Object owner,
            Object currentValue,
            EventType eventType) {
        return tsidGenerator.nextId();
    }

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EventTypeSets.INSERT_ONLY;
    }
}
