package kr.suhsaechan.palim.reconcile.unit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
 * 원천 품목이 어느 정합 단위에 속하나.
 *
 * <p>{@link #factor} 가 <b>세트 상품을 흡수한다.</b> 「1세트 = 본품 2 + 사은품 1」과 「전산
 * 1품목 = 물류 3품목」이 같은 구조가 되므로 별도 세트 기능을 만들지 않는다. 기능이 하나 줄면
 * 그 기능이 만들 수 있었던 오류도 함께 준다.
 *
 * <p>{@link #confirmedAt} 이 <b>제안과 확정을 가른다.</b> 자동 제안은 행으로 남지만 비어 있는
 * 동안에는 대조에 들어가지 않는다 — 사람이 확인하지 않은 추측으로 재고를 합산하면 그 결과가
 * 맞는지 아무도 모른다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "reconcile_unit_member")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconcileUnitMember extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID unitId;

    /** 스냅샷의 {@code source} 와 같은 값. 어느 시스템에서 온 품목인지. */
    @Column(nullable = false, length = 50)
    private String source;

    /** 그 원천에서의 품목 식별자. */
    @Column(nullable = false, length = 255)
    private String itemRef;

    /** 이 품목 하나가 정합 단위 몇 개에 해당하나. */
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal factor;

    /** 비어 있으면 제안 상태다. 대조에 쓰이지 않는다. */
    private Instant confirmedAt;

    private ReconcileUnitMember(UUID tenantId, UUID unitId, String source, String itemRef,
                                BigDecimal factor) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.unitId = unitId;
        this.source = source;
        this.itemRef = itemRef;
        this.factor = factor == null ? BigDecimal.ONE : factor;
    }

    /** 제안 상태로 만든다. 확정하기 전까지 대조에 들어가지 않는다. */
    public static ReconcileUnitMember of(UUID tenantId, UUID unitId, String source, String itemRef,
                                         BigDecimal factor) {
        return new ReconcileUnitMember(tenantId, unitId, source, itemRef, factor);
    }

    /** 사람이 확인했다. 이제부터 대조에 들어간다. */
    public void confirm() {
        this.confirmedAt = Instant.now();
    }

    public boolean isConfirmed() {
        return confirmedAt != null;
    }

    /**
     * 다른 묶음으로 옮긴다.
     *
     * <p>떼었다 다시 붙이는 것과 결과는 같지만, 옮기는 동안 <b>어느 묶음에도 안 속한 순간</b>이
     * 없어야 한다 — 그 사이에 대조가 돌면 그 품목의 재고가 통째로 빠진 채로 계산된다.
     */
    public void moveTo(UUID unitId) {
        this.unitId = unitId;
    }

    /** 환산이 잘못됐을 때 고친다. 이미 담긴 과거 결과는 바뀌지 않는다. */
    public void changeFactor(BigDecimal factor) {
        this.factor = factor == null ? BigDecimal.ONE : factor;
    }
}
