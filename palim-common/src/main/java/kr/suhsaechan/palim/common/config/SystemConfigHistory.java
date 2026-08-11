package kr.suhsaechan.palim.common.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 설정 변경 이력.
 *
 * <p>설정이 시스템 동작을 바꾸는 구조에서는 "언제부터 이상해졌나"의 답이 대개 여기 있다.
 * 점수 순위가 갑자기 달라졌을 때 원인을 추적하는 단서이자 되돌리기의 근거다.
 */
@Getter
@Entity
@Table(name = "system_config_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemConfigHistory extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String configKey;

    /** 최초 등록이면 null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String newValue;

    @Column(length = 50)
    private String changedBy;

    @Column(nullable = false)
    private Instant changedAt;

    private SystemConfigHistory(String configKey, String oldValue, String newValue,
                                String changedBy, Instant changedAt) {
        this.id = UuidV7.generate();
        this.configKey = configKey;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    public static SystemConfigHistory of(String configKey, String oldValue, String newValue,
                                         String changedBy, Instant changedAt) {
        return new SystemConfigHistory(configKey, oldValue, newValue, changedBy, changedAt);
    }
}
