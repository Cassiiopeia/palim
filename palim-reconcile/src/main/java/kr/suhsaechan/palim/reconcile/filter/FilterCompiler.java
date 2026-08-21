package kr.suhsaechan.palim.reconcile.filter;

import java.util.ArrayList;
import java.util.List;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 저장된 줄을 나무로 바꾼다.
 *
 * <p>조건 줄은 <b>AND 로 묶인다.</b> 한 줄 안의 여러 값은 이미 OR 이라 흔한 경우는 줄로 덮이고,
 * 칸을 넘는 OR 이 필요하면 식을 쓴다. 식도 같은 AND 에 함께 걸린다.
 *
 * <p><b>카탈로그에 없는 칸은 조용히 건너뛰지 않는다.</b> 건너뛰면 조건이 빠진 채로 대조가 돌아
 * 틀린 답을 내는데, 화면은 「조건이 걸려 있다」 고 보인다. 원천 구성이 바뀌어 칸이 사라진
 * 것이므로 사람이 고쳐야 할 일이다.
 */
@Component
public class FilterCompiler {

    /** 한 side 의 줄들을 하나의 조건으로. 비면 「전부」. */
    public FilterSpec compile(List<FilterRow> rows) {
        List<FilterNode> nodes = new ArrayList<>();
        for (FilterRow row : rows) {
            if (row.isExpression()) {
                FilterNode parsed = ExpressionParser.parse(row.getExpression());
                if (!parsed.isAll()) {
                    nodes.add(parsed);
                }
                continue;
            }
            nodes.add(toCompare(row));
        }
        if (nodes.isEmpty()) {
            return FilterSpec.all();
        }
        return new FilterSpec(nodes.size() == 1 ? nodes.get(0) : new FilterNode.And(nodes));
    }

    private FilterNode.Compare toCompare(FilterRow row) {
        FilterableField field = FieldCatalog.find(row.getFieldKey())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.FILTER_FIELD_UNKNOWN, row.getFieldKey()));
        if (!row.getOperator().supports(field.type())) {
            throw new BusinessException(ErrorCode.FILTER_OPERATOR_MISMATCH,
                    row.getOperator().label(), field.label());
        }
        if (!row.getOperator().acceptsCount(row.getValues().size())) {
            throw new BusinessException(ErrorCode.FILTER_VALUE_COUNT,
                    row.getOperator().label(), row.getValues().size());
        }
        return new FilterNode.Compare(field, row.getOperator(), row.getValues());
    }
}
