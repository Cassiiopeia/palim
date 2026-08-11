package kr.suhsaechan.palim.common.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 런타임에 바꿀 수 있는 시스템 설정 한 건.
 *
 * <p>설정을 배포 산출물(YAML)에 두면 값 하나 바꾸는 데 재배포가 필요하다. 임계값·가중치처럼
 * <b>운영하면서 계속 조정하는 값</b>은 그 방식으로는 관리되지 않는다. YAML 은 최초 1회 채워 넣는
 * 기본값이고, 그 뒤의 원본은 이 테이블이다.
 *
 * <p>{@link ConfigDefinition} 으로 정의를 선언하면 부팅 시 없는 키만 자동 등록되고 화면에
 * 자동으로 나타난다 — 새 설정을 추가할 때 화면 코드를 고칠 필요가 없다.
 */
@Getter
@Entity
@Table(name = "system_config")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemConfig extends BaseTimeEntity {

    @Id
    private UUID id;

    /** 점 표기 계층 키. 예: {@code influencer.scoring.rule.vsr.points} */
    @Column(nullable = false, length = 200)
    private String configKey;

    /** JSON 표현. 스칼라도 JSON 리터럴로 담는다(예: {@code 14.0}, {@code "text"}, {@code true}). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String configValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConfigValueType valueType;

    /** 설정 화면의 그룹. 예: {@code INFLUENCER_SCORING} */
    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, length = 200)
    private String displayName;

    /** 화면에 그대로 노출되는 설명. "이 값을 올리면 무엇이 어떻게 되는지"를 쓴다. */
    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private boolean editable;

    @Column(precision = 18, scale = 4)
    private BigDecimal minValue;

    @Column(precision = 18, scale = 4)
    private BigDecimal maxValue;

    @Column(nullable = false)
    private int sortOrder;

    @Column(length = 50)
    private String updatedBy;

    @Version
    private Long version;

    private SystemConfig(ConfigDefinition definition) {
        this.id = UuidV7.generate();
        this.configKey = definition.key();
        this.configValue = definition.defaultValue();
        this.valueType = definition.valueType();
        this.category = definition.category();
        this.displayName = definition.displayName();
        this.description = definition.description();
        this.editable = definition.editable();
        this.minValue = definition.minValue();
        this.maxValue = definition.maxValue();
        this.sortOrder = definition.sortOrder();
    }

    /** 정의로부터 최초 등록. 기본값이 곧 초기값이다. */
    public static SystemConfig from(ConfigDefinition definition) {
        return new SystemConfig(definition);
    }

    /** 값 변경. 검증은 {@link SystemConfigService} 가 하고 여기서는 상태만 바꾼다. */
    public void changeValue(String configValue, String updatedBy) {
        this.configValue = configValue;
        this.updatedBy = updatedBy;
    }

    /**
     * 정의 메타데이터 동기화.
     *
     * <p>설명·범위·표시명은 코드가 원본이다. 배포로 문구를 고쳐도 사용자가 설정한 <b>값은
     * 건드리지 않는다</b> — 값의 원본은 DB 이고 메타의 원본은 코드다.
     */
    public void syncMetadata(ConfigDefinition definition) {
        this.valueType = definition.valueType();
        this.category = definition.category();
        this.displayName = definition.displayName();
        this.description = definition.description();
        this.editable = definition.editable();
        this.minValue = definition.minValue();
        this.maxValue = definition.maxValue();
        this.sortOrder = definition.sortOrder();
    }
}
