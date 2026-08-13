package kr.suhsaechan.palim.connector.suggest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.tenant.TenantFilters;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

/**
 * 한 번 연결한 칸의 기억.
 *
 * <p>사전은 우리가 아는 이름만 잡는다. <b>다음에 붙일 시스템의 칸 이름은 알 수 없다.</b>
 * 사람이 한 번 연결해 주면 그 사실을 여기 남기고, 같은 이름이 다시 오면 먼저 골라 둔다.
 *
 * <p>테넌트 안에서만 공유한다. 다른 회사의 연결 습관이 섞이면 안 된다 — 같은 칸 이름이라도
 * 회사마다 다른 뜻으로 쓸 수 있다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "field_mapping_memory")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FieldMappingMemory extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** 대소문자·공백·밑줄을 지운 형태. {@code BAL_QTY} 와 {@code bal qty} 는 같은 이름이다. */
    @Column(nullable = false, length = 200)
    private String sourceField;

    @Column(nullable = false, length = 100)
    private String targetModel;

    @Column(nullable = false, length = 100)
    private String targetField;

    /** 몇 번 이렇게 연결했나. 잦을수록 확신이 커진다. */
    @Column(nullable = false)
    private int hitCount;

    @Column(nullable = false)
    private Instant lastUsedAt;

    private FieldMappingMemory(UUID tenantId, String sourceField, String targetModel,
                               String targetField) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.sourceField = sourceField;
        this.targetModel = targetModel;
        this.targetField = targetField;
        this.hitCount = 1;
        this.lastUsedAt = Instant.now();
    }

    public static FieldMappingMemory of(UUID tenantId, String sourceField, String targetModel,
                                        String targetField) {
        return new FieldMappingMemory(tenantId, sourceField, targetModel, targetField);
    }

    /** 같은 연결을 다시 했다. 확신을 키운다. */
    public void remember() {
        this.hitCount++;
        this.lastUsedAt = Instant.now();
    }
}
