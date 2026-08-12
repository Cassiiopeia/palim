package kr.suhsaechan.palim.connector.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.connector.tenant.TenantFilters;
import org.hibernate.annotations.Filter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 목표 모델의 필드 정의.
 *
 * <p>화면의 매핑 편집기가 이 목록을 오른쪽에 그린다. 표시명·순서를 데이터로 두는 이유는
 * 필드가 100개 가까이 되고 문구가 자주 바뀌기 때문이다 — 코드에 박아 두면 문구 하나 고치는 데
 * 배포가 필요해진다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "target_field")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TargetField extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID targetModelId;

    @Column(nullable = false, length = 63)
    private String fieldKey;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FieldDataType dataType;

    @Column(nullable = false)
    private boolean required;

    @Column(length = 255)
    private String defaultValue;

    @Column(nullable = false)
    private int sortOrder;

    private TargetField(UUID tenantId, UUID targetModelId, String fieldKey, String displayName,
                        FieldDataType dataType, boolean required, String defaultValue,
                        int sortOrder) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.targetModelId = targetModelId;
        this.fieldKey = fieldKey;
        this.displayName = displayName;
        this.dataType = dataType;
        this.required = required;
        this.defaultValue = defaultValue;
        this.sortOrder = sortOrder;
    }

    public static TargetField of(UUID tenantId, UUID targetModelId, String fieldKey,
                                 String displayName, FieldDataType dataType, boolean required,
                                 String defaultValue, int sortOrder) {
        return new TargetField(tenantId, targetModelId, fieldKey, displayName, dataType,
                required, defaultValue, sortOrder);
    }

    public void update(String displayName, FieldDataType dataType, boolean required,
                       String defaultValue, int sortOrder) {
        this.displayName = displayName;
        this.dataType = dataType;
        this.required = required;
        this.defaultValue = defaultValue;
        this.sortOrder = sortOrder;
    }
}
