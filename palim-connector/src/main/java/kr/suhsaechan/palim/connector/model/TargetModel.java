package kr.suhsaechan.palim.connector.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
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
 * 목표 모델 — 데이터를 어디에 담을 것인가.
 *
 * <p>{@code naturalKeyFields} 가 이 엔티티의 핵심이다. "무엇이 같으면 같은 행인가"를 정의하며,
 * 비어 있으면 재실행이 중복 행을 만든다. <b>재시도가 안전해야 사람이 자동화를 켠다.</b>
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "target_model")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TargetModel extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TargetModelKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TargetStorage storage;

    /** BUILTIN 일 때만 값이 있다. */
    @Column(length = 63)
    private String tableName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> naturalKeyFields;

    private TargetModel(UUID tenantId, String code, String name, TargetModelKind kind,
                        TargetStorage storage, String tableName, List<String> naturalKeyFields) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.storage = storage;
        this.tableName = tableName;
        this.naturalKeyFields = naturalKeyFields;
    }

    /** 기본 제공 모델 — 정식 테이블에 적재된다. */
    public static TargetModel builtin(UUID tenantId, String code, String name, String tableName,
                                      List<String> naturalKeyFields) {
        return new TargetModel(tenantId, code, name, TargetModelKind.BUILTIN,
                TargetStorage.TABLE, tableName, naturalKeyFields);
    }

    /** 커스텀 모델 — JSONB 로만 저장한다. 런타임 DDL 을 쓰지 않기 때문이다. */
    public static TargetModel custom(UUID tenantId, String code, String name,
                                     List<String> naturalKeyFields) {
        return new TargetModel(tenantId, code, name, TargetModelKind.CUSTOM,
                TargetStorage.JSONB, null, naturalKeyFields);
    }

    public boolean isCustom() {
        return kind == TargetModelKind.CUSTOM;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeNaturalKey(List<String> naturalKeyFields) {
        this.naturalKeyFields = naturalKeyFields;
    }
}
