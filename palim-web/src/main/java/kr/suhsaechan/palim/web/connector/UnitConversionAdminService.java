package kr.suhsaechan.palim.web.connector;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.unit.UnitConversion;
import kr.suhsaechan.palim.connector.unit.UnitConversionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 단위 환산 규칙 관리.
 *
 * <p>이 화면이 없으면 단위가 명시된 원천을 만났을 때 적재가 {@code UNIT_CONVERSION_NOT_FOUND}
 * 로 막힌 채 <b>DB 를 직접 건드리지 않으면 풀 방법이 없다.</b> 막는 장치를 만들었으면 푸는
 * 수단도 같이 줘야 한다.
 */
@Service
@RequiredArgsConstructor
public class UnitConversionAdminService {

    private final UnitConversionRepository repository;

    @Transactional(readOnly = true)
    public List<UnitConversion> list() {
        return repository.findAll().stream()
                .filter(rule -> ConnectorAdminService.DEFAULT_TENANT.equals(rule.getTenantId()))
                // 전역 규칙을 뒤에 둔다 — 품목별이 우선한다는 조회 순서와 화면 순서를 맞춘다.
                .sorted((a, b) -> {
                    int byGlobal = Boolean.compare(a.getItemRef() == null, b.getItemRef() == null);
                    if (byGlobal != 0) {
                        return byGlobal;
                    }
                    return (a.getFromUnit() + a.getToUnit())
                            .compareTo(b.getFromUnit() + b.getToUnit());
                })
                .toList();
    }

    /**
     * 규칙 등록.
     *
     * @param itemRef 비어 있으면 전역 규칙. 품목별 규칙이 전역보다 우선한다
     */
    @Transactional
    public UnitConversion create(String itemRef, String fromUnit, String toUnit,
                                 BigDecimal factor) {
        if (!StringUtils.hasText(fromUnit) || !StringUtils.hasText(toUnit)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "단위를 모두 입력하세요");
        }
        if (fromUnit.trim().equals(toUnit.trim())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "같은 단위끼리는 규칙이 필요 없습니다");
        }
        if (factor == null || factor.signum() <= 0) {
            // 0 이나 음수 배율은 수량을 조용히 0 이나 음수로 만든다. 대사가 통째로 무의미해진다.
            throw new BusinessException(ErrorCode.INVALID_INPUT, "배율은 0보다 커야 합니다");
        }

        String item = StringUtils.hasText(itemRef) ? itemRef.trim() : null;
        String from = fromUnit.trim();
        String to = toUnit.trim();

        // 같은 범위의 규칙만 중복으로 본다. 전역 규칙이 있어도 품목별 예외는 만들 수 있어야 한다.
        if (repository.existsRule(ConnectorAdminService.DEFAULT_TENANT, item, from, to)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "이미 있는 규칙입니다: %s → %s".formatted(from, to));
        }

        UnitConversion rule = item == null
                ? UnitConversion.global(ConnectorAdminService.DEFAULT_TENANT, from, to, factor)
                : UnitConversion.forItem(ConnectorAdminService.DEFAULT_TENANT, item, from, to,
                        factor);
        return repository.save(rule);
    }

    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
    }
}
