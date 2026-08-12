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
 * <p>{@code itemRef} 가 {@link #GLOBAL}(빈 문자열)이면 전역 규칙이다. 조회는 품목별 → 전역
 * 순으로 한다.
 *
 * <p><b>전역을 {@code null} 이 아니라 빈 문자열로 표현한다.</b> {@code null} 로 두면 유니크
 * 인덱스가 NULL 을 서로 다른 값으로 취급해 전역 규칙이 무한히 중복 등록되고, 조회 조건마다
 * {@code is null} 분기가 따라붙어 삼항 논리 실수를 부른다. 운영 DB(PostgreSQL 14)에는
 * {@code NULLS NOT DISTINCT} 도 없다.
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

    /** 전역 규칙을 나타내는 {@code itemRef} 값. */
    public static final String GLOBAL = "";

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** {@link #GLOBAL} 이면 전역 규칙. */
    @Column(nullable = false, length = 255)
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
        this.itemRef = normalizeItemRef(itemRef);
        this.fromUnit = fromUnit;
        this.toUnit = toUnit;
        this.factor = factor;
    }

    /**
     * 조회·저장에 쓰는 {@code itemRef} 정규화.
     *
     * <p>화면에서 품목을 비워두면 {@code null} 이나 공백 문자열이 올라온다. 그대로 저장하면
     * 전역 규칙이 세 가지 표현({@code null}, {@code ""}, {@code "  "})으로 갈라져 중복 검사가
     * 뚫린다. 들어오는 모든 경로가 이 메서드를 지나게 한다.
     */
    public static String normalizeItemRef(String itemRef) {
        return itemRef == null || itemRef.isBlank() ? GLOBAL : itemRef.trim();
    }

    /** 전역 규칙 — 모든 품목에 적용된다. */
    public static UnitConversion global(UUID tenantId, String fromUnit, String toUnit,
                                        BigDecimal factor) {
        return new UnitConversion(tenantId, GLOBAL, fromUnit, toUnit, factor);
    }

    /** 전역 규칙인가. */
    public boolean isGlobal() {
        return GLOBAL.equals(itemRef);
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
