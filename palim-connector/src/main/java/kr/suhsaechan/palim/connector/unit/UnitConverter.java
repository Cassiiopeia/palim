package kr.suhsaechan.palim.connector.unit;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 단위 환산.
 *
 * <p>실패 조건을 좁게 잡는다.
 *
 * <table border="1">
 *   <caption>분기</caption>
 *   <tr><td>단위가 비어 있음</td><td>기준 단위로 통과 — 단위 컬럼이 없는 원천이 흔하다</td></tr>
 *   <tr><td>단위 = 기준 단위</td><td>규칙 조회 없이 통과</td></tr>
 *   <tr><td>단위가 다르고 규칙 있음</td><td>환산</td></tr>
 *   <tr><td>단위가 다르고 규칙 없음</td><td><b>실패</b></td></tr>
 * </table>
 *
 * <p>마지막 줄이 이 클래스의 존재 이유다. 조용히 1:1 로 넘기면 BOX 12개가 EA 12개로 둔갑하고,
 * 그 오류는 대사 결과가 이상해질 때까지 아무도 모른다. 반대로 첫 줄이 없으면 단위 개념이 없는
 * 원천이 첫 적재부터 전부 실패한다 — 실측한 두 원천이 모두 그렇다.
 */
@Component
@RequiredArgsConstructor
public class UnitConverter {

    private final UnitConversionRepository repository;

    /**
     * @param itemRef     품목별 규칙 조회용. 없으면 전역 규칙만 본다
     * @param unit        원천이 준 단위. 비어 있으면 환산하지 않는다
     * @param defaultUnit 커넥터의 기준 단위
     */
    public ConvertedQuantity convert(UUID tenantId, String itemRef, BigDecimal quantity,
                                     String unit, String defaultUnit) {
        if (!StringUtils.hasText(unit) || unit.trim().equals(defaultUnit)) {
            return new ConvertedQuantity(quantity, unit, quantity, defaultUnit);
        }

        String from = unit.trim();
        // 품목별 규칙이 앞에 오도록 리포지토리가 정렬해 준다. 첫 값만 쓴다.
        List<BigDecimal> factors = repository.findFactors(tenantId, itemRef, from, defaultUnit);
        if (factors.isEmpty()) {
            throw new BusinessException(ErrorCode.UNIT_CONVERSION_NOT_FOUND, from, defaultUnit);
        }

        return new ConvertedQuantity(quantity, from,
                quantity.multiply(factors.getFirst()), defaultUnit);
    }
}
