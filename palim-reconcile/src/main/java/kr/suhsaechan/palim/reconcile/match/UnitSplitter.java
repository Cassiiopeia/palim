package kr.suhsaechan.palim.reconcile.match;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnit;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitService;
import kr.suhsaechan.palim.reconcile.define.Pairing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 묶음을 <b>고른 기준으로 갈라</b> 여러 묶음으로 만든다.
 *
 * <p>자동 후보는 이름이 닮은 것을 <b>통째로 하나</b>로 묶는다. 그런데 로트 셋이 든 묶음은
 * 「+50 하나」 로 볼 수도 있고 「+24 · +26 · 맞음」 셋으로 볼 수도 있다 — <b>어느 쪽이 맞는
 * 운영인지는 회사가 정할 일</b>이고, 쪼갤 길이 없으면 코드가 정해 버린 셈이 된다.
 *
 * <p><b>가르는 기준은 뜯어보기와 같은 것을 쓴다.</b> 「이렇게 갈라서 보고 있다」 와 「이렇게
 * 갈라서 묶겠다」 는 같은 이야기인데 기준이 서로 다르면, 화면에서 본 대로 쪼갰는데 결과가
 * 다르게 나온다.
 *
 * <p><b>아직 안 묶인 줄도 같은 길로 처리한다</b> — 먼저 통째로 묶고 곧바로 쪼갠다. 한
 * 트랜잭션 안이라 중간 상태가 밖으로 보이지 않고, 코드가 한 벌로 끝난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnitSplitter {

    private final UnitBreakdown breakdowns;
    private final ReconcileUnitService units;

    /**
     * 갈랐을 때 어떤 묶음들이 되는지 <b>미리 보여준다.</b>
     *
     * <p>쪼개기는 되돌리기가 번거롭다(다시 합쳐야 한다). 누르기 전에 결과를 못 보면 사람은
     * 안 누르거나, 눌러 놓고 수습한다.
     */
    @Transactional(readOnly = true)
    protected List<UnitBreakdown.Line> preview(UUID tenantId, UUID unitId, Pairing pairing,
                                               BreakdownAxis axis) {
        // 지금 담긴 자료로 본다. 쪼개기는 «현재» 를 기준으로 하므로 옛 회차를 되짚지 않는다.
        return breakdowns.of(tenantId, unitId, pairing, UnitBreakdown.At.now(), axis).lines();
    }

    /**
     * <b>아직 안 묶인 줄</b>이 이 기준으로 어떻게 갈라지는지 미리 본다.
     *
     * <p>묶어 놓고 쪼개면 결과는 같지만, <b>보기만 하려는데 자료가 바뀌면</b> 안 된다.
     * 그래서 여기서는 줄에 든 품목만으로 계산하고 아무것도 저장하지 않는다.
     */
    public List<Group> previewUnlinked(MatchBoard.Row row, BreakdownAxis axis) {
        return groupsOf(row, axis);
    }

    /**
     * 아직 안 묶인 줄을 <b>갈라서 여러 묶음으로</b> 만든다.
     *
     * <p>통째로 하나로 묶는 길과 나란히 있어야 한다. 하나뿐이면 코드가 정해 버리는 셈이다.
     */
    @Transactional
    public List<ReconcileUnit> linkSeparately(UUID tenantId, MatchBoard.Row row,
                                              BreakdownAxis axis) {
        List<ReconcileUnit> created = new ArrayList<>();
        for (Group group : groupsOf(row, axis)) {
            List<ReconcileUnitService.Pick> picks = group.items().stream()
                    .map(item -> new ReconcileUnitService.Pick(
                            item.source(), item.itemRef(), java.math.BigDecimal.ONE))
                    .toList();
            created.add(units.link(picks,
                    "U-" + UUID.randomUUID().toString().substring(0, 8), group.name(), "EA"));
        }
        log.info("나눠서 묶었다 — 기준={} 새 묶음={}개", axis.token(), created.size());
        return created;
    }

    /**
     * 줄에 든 품목을 기준대로 갈라 무리로 만든다.
     *
     * <p>이 계산이 <b>미리보기와 실제 묶기에 같이 쓰인다.</b> 두 벌로 두면 미리 본 것과 다른
     * 결과가 나오는데, 그러면 미리보기가 있으나 마나다.
     */
    private List<Group> groupsOf(MatchBoard.Row row, BreakdownAxis axis) {
        if (!axis.pairs()) {
            // 짝짓지 않기로 했으면 품목 하나가 곧 한 묶음이다.
            return row.items().stream()
                    .map(item -> new Group(item.displayName(), List.of(item)))
                    .toList();
        }
        // 기준이 «칸» 이어도 아직 안 묶인 줄에서는 그 칸 값을 알 수 없다(스냅샷을 다시 읽어야
        // 한다). 여기서는 품명으로 가른다 — 다듬지 않은 원본 이름이라 로트가 구분된다.
        java.util.Map<String, List<MatchBoard.Item>> grouped = new java.util.LinkedHashMap<>();
        for (MatchBoard.Item item : row.items()) {
            String key = item.displayName().replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }
        List<Group> groups = new ArrayList<>();
        grouped.forEach((key, items) -> groups.add(new Group(items.getFirst().displayName(), items)));
        return groups;
    }

    /** 갈라 놓은 무리 하나 — 이것이 곧 한 묶음이 된다. */
    public record Group(String name, List<MatchBoard.Item> items) {

        public java.math.BigDecimal leftTotal(String leftSource) {
            return items.stream().filter(item -> item.source().equals(leftSource))
                    .map(MatchBoard.Item::effectiveQuantity)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        }

        public java.math.BigDecimal rightTotal(String leftSource) {
            return items.stream().filter(item -> !item.source().equals(leftSource))
                    .map(MatchBoard.Item::effectiveQuantity)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        }

        /** 이 무리가 한 묶음이 되면 차이가 얼마인가. 가르기 전에 이걸 봐야 판단이 선다. */
        public String diffText(String leftSource) {
            java.math.BigDecimal value = leftTotal(leftSource).subtract(rightTotal(leftSource));
            return value.signum() == 0 ? "맞음"
                    : (value.signum() > 0 ? "+" : "") + MatchBoard.amount(value);
        }

        public boolean hasDiff(String leftSource) {
            return leftTotal(leftSource).compareTo(rightTotal(leftSource)) != 0;
        }
    }

    /**
     * 이 기준으로 갈라 여러 묶음으로 만든다.
     *
     * @return 새로 생긴 묶음들. 가를 것이 하나뿐이면 빈 목록
     */
    @Transactional
    public List<ReconcileUnit> split(UUID tenantId, UUID unitId, Pairing pairing,
                                     BreakdownAxis axis) {
        // 가르는 기준은 «뜯어보기와 같은 것» 을 쓴다. 정의가 안 보기로 한 창고 값으로 가르면
        // 화면에 없던 이유로 묶음이 갈라지고, 쪼개기는 되돌리기가 번거롭다.
        List<UnitBreakdown.Line> lines = preview(tenantId, unitId, pairing, axis);
        if (lines.size() < 2) {
            // 가를 것이 없다. 오류가 아니라 «이 기준으로는 하나로 남는다» 는 사실이다.
            return List.of();
        }

        List<ReconcileUnitService.Group> groups = new ArrayList<>();
        for (UnitBreakdown.Line line : lines) {
            List<ReconcileUnitService.Member> picked = new ArrayList<>();
            if (line.left() != null) {
                picked.add(new ReconcileUnitService.Member(
                        line.left().source(), line.left().itemRef()));
            }
            if (line.right() != null) {
                picked.add(new ReconcileUnitService.Member(
                        line.right().source(), line.right().itemRef()));
            }
            groups.add(new ReconcileUnitService.Group(line.label(), picked));
        }

        List<ReconcileUnit> created = units.split(unitId, groups);
        log.info("묶음을 갈랐다 — 묶음={} 기준={} 새 묶음={}개", unitId, axis.token(), created.size());
        return created;
    }
}
