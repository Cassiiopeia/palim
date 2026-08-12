package kr.suhsaechan.palim.connector.define;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
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
 * 매핑 버전.
 *
 * <p>정의를 덮어쓰지 않고 버전을 올린다. 실행 기록이 버전 번호를 값으로 갖고 있어 <b>"지난달
 * 데이터가 왜 이런가"에 답할 수 있다.</b> 덮어쓰는 구조였다면 과거를 설명할 방법이 사라진다.
 *
 * <p>{@code sourceSchema} 는 확정 당시의 원천 필드 목록이다. 매 실행마다 이것과 대조해
 * 양식 변화를 잡는다 — 조용히 잘못된 데이터가 들어가는 것이 이 시스템의 최악 실패다.
 */
@Getter
@Entity
@Table(name = "connector_mapping")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorMapping extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID connectorId;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MappingStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> sourceSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Map<String, Object>> hooks;

    private Instant activatedAt;

    private ConnectorMapping(UUID tenantId, UUID connectorId, int version,
                             Map<String, Object> sourceSchema) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.connectorId = connectorId;
        this.version = version;
        this.status = MappingStatus.DRAFT;
        this.sourceSchema = sourceSchema;
        this.hooks = List.of();
    }

    public static ConnectorMapping draft(UUID tenantId, UUID connectorId, int version,
                                         Map<String, Object> sourceSchema) {
        return new ConnectorMapping(tenantId, connectorId, version, sourceSchema);
    }

    /**
     * 확정.
     *
     * <p>커넥터당 ACTIVE 는 하나뿐이며 그 보장은 DB 부분 유니크 인덱스가 한다. 애플리케이션
     * 검증에만 맡기면 동시 요청에서 뚫린다.
     */
    public void activate() {
        this.status = MappingStatus.ACTIVE;
        this.activatedAt = Instant.now();
    }

    public void archive() {
        this.status = MappingStatus.ARCHIVED;
    }

    public void replaceSchema(Map<String, Object> sourceSchema) {
        this.sourceSchema = sourceSchema;
    }

    public void replaceHooks(List<Map<String, Object>> hooks) {
        this.hooks = hooks;
    }

    public boolean isActive() {
        return status == MappingStatus.ACTIVE;
    }

    /** 확정 당시의 원천 필드 목록. 드리프트 감지의 기준이다. */
    @SuppressWarnings("unchecked")
    public List<String> confirmedFields() {
        Object fields = sourceSchema.get("fields");
        return fields instanceof List<?> list ? (List<String>) list : List.of();
    }
}
