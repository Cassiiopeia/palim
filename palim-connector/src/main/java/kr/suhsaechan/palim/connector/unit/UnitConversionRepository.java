package kr.suhsaechan.palim.connector.unit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UnitConversionRepository extends JpaRepository<UnitConversion, UUID> {

    /**
     * 품목별 규칙을 먼저, 없으면 전역 규칙을 쓴다.
     *
     * <p>{@code itemRef IS NULL} 이 전역 규칙이다. 정렬로 품목별을 앞에 두어 우선순위를 한
     * 쿼리로 표현한다. 결과를 {@code List} 로 받는 이유는 두 규칙이 모두 존재하는 것이 정상이며
     * {@code Optional} 로 받으면 그때 예외가 나기 때문이다 — 첫 행만 취한다.
     */
    @Query("""
            select c.factor from UnitConversion c
            where c.tenantId = :tenantId
              and (c.itemRef = :itemRef or c.itemRef is null)
              and c.fromUnit = :fromUnit
              and c.toUnit = :toUnit
            order by case when c.itemRef is null then 1 else 0 end
            """)
    List<BigDecimal> findFactors(@Param("tenantId") UUID tenantId,
                                 @Param("itemRef") String itemRef,
                                 @Param("fromUnit") String fromUnit,
                                 @Param("toUnit") String toUnit);

    default Optional<BigDecimal> findRule(UUID tenantId, String itemRef, String fromUnit,
                                          String toUnit) {
        return findFactors(tenantId, itemRef, fromUnit, toUnit).stream().findFirst();
    }

    /**
     * 같은 <b>범위</b>의 규칙이 이미 있는지.
     *
     * <p>{@link #findFactors} 를 중복 검사에 쓰면 안 된다. 그쪽은 전역 규칙까지 함께 찾으므로,
     * 전역 규칙이 하나라도 있으면 <b>품목별 예외 규칙을 만들 수 없게 된다</b> — 품목별의 존재
     * 이유가 "전역과 다르게"인데 그것을 막는 셈이다.
     */
    @Query("""
            select count(c) > 0 from UnitConversion c
            where c.tenantId = :tenantId
              and ((:itemRef is null and c.itemRef is null) or c.itemRef = :itemRef)
              and c.fromUnit = :fromUnit
              and c.toUnit = :toUnit
            """)
    boolean existsRule(@Param("tenantId") UUID tenantId,
                       @Param("itemRef") String itemRef,
                       @Param("fromUnit") String fromUnit,
                       @Param("toUnit") String toUnit);
}
