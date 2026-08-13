package kr.suhsaechan.palim.connector.define;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.tenant.TenantFilters;
import org.hibernate.annotations.Filter;
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
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
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

    /**
     * 연결 상태. 화면이 "다음에 무엇을 하라"고 말하는 근거다.
     *
     * <p>등록만 하고 검증하지 않은 것과 검증까지 끝난 것은 다르다. 구분하지 않으면 사용자는
     * 등록을 마친 뒤 멈추고, 첫 수집에서야 안 된다는 것을 알게 된다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectionStatus connectionStatus;

    /**
     * 등록된 인증키의 종류.
     *
     * <p>테스트 키는 업무 API 를 한 번 성공시키면 소진되는 경우가 있어 매일 수집에 쓸 수 없다.
     * 종류를 기억해야 "정식 키로 교체하세요"라고 안내할 수 있다.
     */
    @Column(length = 10)
    private String credentialKind;

    /** 마지막으로 검증에 성공한 시각. 오래됐으면 다시 확인해 보라고 알릴 수 있다. */
    private Instant lastVerifiedAt;

    /**
     * 마지막 실패 사유.
     *
     * <p>화면을 닫으면 사라지는 오류는 없는 것과 같다. 키 만료처럼 시간이 지나 드러나는 실패는
     * 남겨두지 않으면 원인을 다시 찾아야 한다.
     */
    @Column(length = 500)
    private String lastError;

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
        this.connectionStatus = ConnectionStatus.NOT_CONFIGURED;
    }

    /** 검증 성공. 키 종류에 따라 아직 할 일이 남았는지가 갈린다. */
    public void markVerified(boolean live) {
        this.connectionStatus = live
                ? ConnectionStatus.VERIFIED_LIVE : ConnectionStatus.VERIFIED_TEST;
        this.credentialKind = live ? "LIVE" : "TEST";
        this.lastVerifiedAt = Instant.now();
        this.lastError = null;
    }

    /** 검증 실패. 사유를 남겨 나중에 원인을 되짚을 수 있게 한다. */
    public void markFailed(String reason) {
        this.connectionStatus = ConnectionStatus.FAILED;
        this.lastError = reason == null ? null
                : reason.substring(0, Math.min(reason.length(), 500));
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
