package kr.suhsaechan.palim.connector.define;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.connector.tenant.TenantFilters;
import org.hibernate.annotations.Filter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 원천 필드 → 목표 필드 연결.
 *
 * <p>화면 매핑 편집기에서 좌우를 잇는 선 하나가 이 행 하나다. AI 는 이 행들의 <b>초안</b>만
 * 만들고, 확정은 사람이 한다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "connector_field_map")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorFieldMap extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID mappingId;

    @Column(nullable = false, length = 255)
    private String sourceField;

    @Column(nullable = false, length = 63)
    private String targetFieldKey;

    /** {@code {"type":"DATE_FORMAT","params":{"pattern":"yyyy-MM-dd"}}} 형태. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> transformRule;

    @Column(nullable = false)
    private int sortOrder;

    private ConnectorFieldMap(UUID tenantId, UUID mappingId, String sourceField,
                              String targetFieldKey, Map<String, Object> transformRule,
                              int sortOrder) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.mappingId = mappingId;
        this.sourceField = sourceField;
        this.targetFieldKey = targetFieldKey;
        this.transformRule = transformRule;
        this.sortOrder = sortOrder;
    }

    public static ConnectorFieldMap of(UUID tenantId, UUID mappingId, String sourceField,
                                       String targetFieldKey, Map<String, Object> transformRule,
                                       int sortOrder) {
        return new ConnectorFieldMap(tenantId, mappingId, sourceField, targetFieldKey,
                transformRule, sortOrder);
    }

    public void changeRule(Map<String, Object> transformRule) {
        this.transformRule = transformRule;
    }
}
