package kr.suhsaechan.palim.connector.define;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 연동 하나 — 어떤 원천에서 어떤 목표 모델로 가져오는가.
 *
 * <p>이것이 코드가 아니라 <b>DB 행</b>이라는 점이 이 프레임워크의 전부다. 새 원천이 붙어도
 * 배포가 필요 없고, 양식이 바뀌면 매핑 버전만 올린다.
 *
 * <p>{@code credentialRef} 는 <b>참조일 뿐 값이 아니다.</b> API 키·비밀번호는 암호화 저장소에
 * 있다. 이 저장소는 PUBLIC 이고 DB 덤프가 유출돼도 키가 새지 않아야 한다.
 */
@Getter
@Entity
@Table(name = "connector")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Connector extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private UUID targetModelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SourceType sourceType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> sourceConfig;

    @Column(length = 100)
    private String credentialRef;

    /**
     * 단위 컬럼이 없는 원천의 기준 단위.
     *
     * <p>실측한 두 원천 모두 단위 컬럼이 없다. 이 값이 없으면 "규칙이 없어 환산 불가"로
     * 전 행이 실패한다.
     */
    @Column(nullable = false, length = 20)
    private String defaultUnit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncrementalMode incrementalMode;

    @Column(length = 63)
    private String cursorField;

    @Column(length = 255)
    private String cursorValue;

    @Column(length = 100)
    private String scheduleCron;

    @Column(nullable = false)
    private boolean enabled;

    private Connector(UUID tenantId, String code, String name, UUID targetModelId,
                      SourceType sourceType, String defaultUnit) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.targetModelId = targetModelId;
        this.sourceType = sourceType;
        this.sourceConfig = Map.of();
        this.defaultUnit = defaultUnit;
        this.incrementalMode = IncrementalMode.FULL;
        this.enabled = true;
    }

    public static Connector of(UUID tenantId, String code, String name, UUID targetModelId,
                               SourceType sourceType, String defaultUnit) {
        return new Connector(tenantId, code, name, targetModelId, sourceType, defaultUnit);
    }

    public void configureSource(Map<String, Object> sourceConfig, String credentialRef) {
        this.sourceConfig = sourceConfig;
        this.credentialRef = credentialRef;
    }

    public void enableIncremental(String cursorField) {
        this.incrementalMode = IncrementalMode.INCREMENTAL;
        this.cursorField = cursorField;
    }

    public void schedule(String cron) {
        this.scheduleCron = cron;
    }

    /**
     * 커서 전진.
     *
     * <p><b>성공한 실행만 호출한다.</b> 실패했는데 커서가 넘어가면 그 구간 데이터는 영원히
     * 들어오지 않는다. 중복은 자연키 UPSERT 가 흡수하므로, 같은 구간을 다시 읽는 쪽이 안전하다.
     */
    public void advanceCursor(String cursorValue) {
        if (incrementalMode == IncrementalMode.INCREMENTAL) {
            this.cursorValue = cursorValue;
        }
    }

    public void disable() {
        this.enabled = false;
    }
}
