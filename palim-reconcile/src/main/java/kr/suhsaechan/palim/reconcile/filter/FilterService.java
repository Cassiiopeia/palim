package kr.suhsaechan.palim.reconcile.filter;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.reconcile.define.Pairing;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조건을 읽어 오는 <b>한 자리</b>.
 *
 * <p>화면도 엔진도 여기로만 조건을 얻는다. 두 곳이 각자 읽으면 한쪽이 식을 빠뜨리거나 정렬을
 * 다르게 하는 날이 오고, 그 차이는 숫자로만 드러난다.
 */
@Service
@RequiredArgsConstructor
public class FilterService {

    private final FilterRowRepository rows;
    private final FilterCompiler compiler;

    /** 그 원천 쪽 조건 줄. 화면이 편집기를 그리는 데 쓴다. */
    @Transactional(readOnly = true)
    public List<FilterRow> rowsOf(UUID definitionId, FilterSide side) {
        return rows.findByDefinitionIdOrderBySideAscOrdinalAsc(definitionId).stream()
                .filter(row -> row.getSide() == side)
                .toList();
    }

    /** 그 원천 쪽 조건. 비어 있으면 전부 본다. */
    @Transactional(readOnly = true)
    public FilterSpec specOf(UUID definitionId, FilterSide side) {
        return compiler.compile(rowsOf(definitionId, side));
    }

    /**
     * 견주는 방식 한 묶음.
     *
     * <p>{@code Pairing} 을 만드는 길을 여기 하나로 둔다. 새 조회를 만들 때 원천을 넘기는
     * 순간 조건도 함께 넘어가므로 <b>빠뜨릴 수가 없다</b> — 이것이 그 타입의 존재 이유다.
     */
    @Transactional(readOnly = true)
    public Pairing pairingOf(ReconcileDefinition definition) {
        List<FilterRow> all =
                rows.findByDefinitionIdOrderBySideAscOrdinalAsc(definition.getId());
        return new Pairing(definition.getLeftSource(), definition.getRightSource(),
                compiler.compile(sideOf(all, FilterSide.LEFT)),
                compiler.compile(sideOf(all, FilterSide.RIGHT)),
                definition.getCompareField());
    }

    private static List<FilterRow> sideOf(List<FilterRow> all, FilterSide side) {
        return all.stream().filter(row -> row.getSide() == side).toList();
    }
}
