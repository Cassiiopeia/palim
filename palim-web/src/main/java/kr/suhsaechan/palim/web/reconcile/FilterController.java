package kr.suhsaechan.palim.web.reconcile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.filter.ExpressionParser;
import kr.suhsaechan.palim.reconcile.filter.FilterCompiler;
import kr.suhsaechan.palim.reconcile.filter.FilterOperator;
import kr.suhsaechan.palim.reconcile.filter.FilterRow;
import kr.suhsaechan.palim.reconcile.filter.FilterRowRepository;
import kr.suhsaechan.palim.reconcile.filter.FilterSide;
import kr.suhsaechan.palim.reconcile.filter.FilterValues;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * <b>무엇을 볼지</b> 정한다.
 *
 * <p>재고를 맡긴 곳은 자기가 보관 중인 것만 안다. 전산 쪽 창고를 전부 더해 견주면 맡기지 않은
 * 물량만큼 무조건 어긋나고, 그 어긋남은 맞던 품목까지 틀린 것으로 보이게 만든다. 그런데 걸러야
 * 하는 것은 창고만이 아니다 — 불량 재고, 유통기한이 지난 것, 원천이 주는 고유 구분값.
 *
 * <p>한 side 의 줄을 <b>통째로 갈아 끼운다.</b> 줄마다 id 를 주고받으면 화면을 띄운 뒤 다른
 * 사람이 줄을 지웠을 때 「없는 줄을 고치려 했습니다」 가 된다. 통째로 보내면 마지막에 저장한
 * 사람의 뜻이 그대로 남는다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class FilterController {

    private final FilterRowRepository rows;
    private final FilterCompiler compiler;
    private final ErrorMessageResolver errorMessages;

    @PostMapping("/reconcile/{id}/filters")
    @Transactional
    public String save(@PathVariable UUID id,
                       @RequestParam FilterSide side,
                       @RequestParam(name = "fieldKey", required = false) List<String> fieldKeys,
                       @RequestParam(name = "operator", required = false)
                       List<FilterOperator> operators,
                       @RequestParam(name = "values", required = false) List<String> values,
                       @RequestParam(required = false, defaultValue = "") String expression,
                       RedirectAttributes redirect) {

        List<FilterRow> next;
        try {
            next = buildRows(id, side, fieldKeys, operators, values, expression);
            // 저장하기 전에 실제로 SQL 이 되는지 확인한다. 저장하고 나서 드러나면 대조가 도는
            // 순간까지 「걸려 있다」 고 보이는데 실제로는 죽는다.
            compiler.compile(next);
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError", errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
            return "redirect:/reconcile/" + id + "#filters";
        }

        rows.deleteByDefinitionIdAndSide(id, side);
        rows.saveAll(next);

        log.info("대조 조건 변경 — 정의={} 쪽={} 줄={}개 식={}",
                id, side, next.size(), expression.isBlank() ? "없음" : "있음");
        redirect.addFlashAttribute("flashSuccess",
                next.isEmpty()
                        ? "조건을 지웠습니다. 이제 전부 더해서 견줍니다."
                        : "볼 조건을 정했습니다. 다음 대조부터 적용됩니다.");
        return "redirect:/reconcile/" + id + "#filters";
    }

    /**
     * 화면이 보낸 세 목록을 줄로 맞춘다.
     *
     * <p>길이가 어긋나면 <b>저장하지 않는다.</b> 짧은 쪽에 맞춰 자르면 사람이 적은 조건 일부가
     * 조용히 사라지고, 화면은 「저장했습니다」 라고 말한다.
     */
    private List<FilterRow> buildRows(UUID definitionId, FilterSide side,
                                      List<String> fieldKeys, List<FilterOperator> operators,
                                      List<String> values, String expression) {
        UUID tenantId = TenantContext.current();
        List<FilterRow> built = new ArrayList<>();

        if (fieldKeys != null && !fieldKeys.isEmpty()) {
            if (operators == null || values == null
                    || fieldKeys.size() != operators.size()
                    || fieldKeys.size() != values.size()) {
                throw new BusinessException(ErrorCode.FILTER_VALUE_COUNT,
                        "조건 줄", fieldKeys.size());
            }
            for (int i = 0; i < fieldKeys.size(); i++) {
                built.add(FilterRow.field(tenantId, definitionId, side, i,
                        fieldKeys.get(i), operators.get(i),
                        FilterValues.split(values.get(i))));
            }
        }

        if (!expression.isBlank()) {
            // 읽을 수 없으면 여기서 터진다. 저장하고 도는 순간까지 미루지 않는다.
            ExpressionParser.parse(expression);
            built.add(FilterRow.expression(tenantId, definitionId, side,
                    built.size(), expression.trim()));
        }
        return built;
    }
}
