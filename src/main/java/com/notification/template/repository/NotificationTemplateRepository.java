package com.notification.template.repository;

import com.notification.notification.domain.NotificationChannel;
import com.notification.template.domain.NotificationTemplate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByIdAndDeletedFalse(Long id);

    Optional<NotificationTemplate>
            findTopByCodeAndChannelAndLocaleAndDeletedFalseOrderByVersionDesc(
                    String code, NotificationChannel channel, String locale);

    List<NotificationTemplate> findByCodeAndDeletedFalseOrderByVersionDesc(String code);

    /**
     * 특정 템플릿(code + channel + locale)의 최대 버전을 반환합니다. 레코드가 없으면 0을 반환합니다.
     *
     * <p><b>주의: deleted 조건을 의도적으로 제외합니다.</b><br>
     * soft-delete된 버전을 포함해야 버전 번호의 단조 증가(monotonically increasing)가 보장됩니다. {@code deleted = false}
     * 조건을 추가하면 아래 상황에서 unique constraint 위반이 발생합니다.
     *
     * <ol>
     *   <li>v1 생성 후 soft-delete → DB에 {@code (code, channel, locale, version=1, deleted=true)} 존재
     *   <li>{@code findMaxVersion(deleted=false)} → 살아있는 레코드 없음 → {@code COALESCE = 0}
     *   <li>{@code nextVersion = 1} 계산 → INSERT 시도 → v1이 이미 존재하므로 unique constraint 위반
     * </ol>
     */
    @Query(
            """
            SELECT COALESCE(MAX(t.version), 0)
            FROM NotificationTemplate t
            WHERE t.code = :code AND t.channel = :channel AND t.locale = :locale
            """)
    int findMaxVersion(
            @Param("code") String code,
            @Param("channel") NotificationChannel channel,
            @Param("locale") String locale);
}
