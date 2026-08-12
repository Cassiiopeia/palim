package kr.suhsaechan.palim.connector.unit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.connector.tenant.TenantFilters;
import org.hibernate.annotations.Filter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 단위 환산 규칙.
 *
 * <p>{@code itemRef} 가 {@code null} 이면 전역 규칙이다. 조회는 품목별 → 전역 순으로 한다.
 *
 * <p>이 테이블이 비어 있어도 대부분의 원천은 정상 적재된다 — 단위 컬럼 자체가 없는 원천이
 * 흔하기 때문이다. 규칙이 필요한 순간은 원천이 <b>단위를 명시했는데</b> 기준 단위와 다를 때뿐이다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "unit_conversion")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnitConversion extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** null 이면 전역 규칙. */
    @Column(length = 255)
    private String itemRef;

    @Column(nullable = false, length = 20)
    private String fromUnit;

    @Column(nullable = false, length = 20)
    private String toUnit;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal factor;

    private UnitConversion(UUID tenantId, String itemRef, String fromUnit, String toUnit,
                           BigDecimal factor) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.itemRef = itemRef;
        this.fromUnit = fromUnit;
        this.toUnit = toUnit;
        this.factor = factor;
    }

    /** 전역 규칙 — 모든 품목에 적용된다. */
    public static UnitConversion global(UUID tenantId, String fromUnit, String toUnit,
                                        BigDecimal factor) {
        return new UnitConversion(tenantId, null, fromUnit, toUnit, factor);
    }

    /** 품목별 규칙 — 전역 규칙보다 우선한다. */
    public static UnitConversion forItem(UUID tenantId, String itemRef, String fromUnit,
                                         String toUnit, BigDecimal factor) {
        return new UnitConversion(tenantId, itemRef, fromUnit, toUnit, factor);
    }

    public void changeFactor(BigDecimal factor) {
        this.factor = factor;
    }
}
