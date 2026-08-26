package kr.suhsaechan.palim.reconcile.engine;

import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;

/**
 * 대조 하나가 <b>어떻게 끝났나</b>.
 *
 * <p>하루 요약을 만들려면 대조를 다 돌린 <b>뒤에</b> 전체를 봐야 한다. 도는 중에 하나씩
 * 알리면 통이 대조 수만큼 갈리고, 그러면 「하루 한 통」 이 성립하지 않는다.
 *
 * @param alertable 알릴 만한 차이 건수
 * @param blockedDays 며칠째 막혔나. 0 이면 안 막혔다
 */
public record DefinitionOutcome(
        ReconcileDefinition definition,
        ReconcileRun run,
        int alertable,
        int blockedDays
) {

    public static DefinitionOutcome succeeded(ReconcileDefinition definition, ReconcileRun run,
                                              int alertable) {
        return new DefinitionOutcome(definition, run, alertable, 0);
    }

    public static DefinitionOutcome failed(ReconcileDefinition definition, ReconcileRun run,
                                           int blockedDays) {
        return new DefinitionOutcome(definition, run, 0, blockedDays);
    }

    public boolean isSuccess() {
        return run != null && run.isSuccess();
    }

    /** 알릴 기준을 안 정했는가. 그러면 차이가 나도 조용하다. */
    public boolean hasNoThreshold() {
        return definition.getAlertThreshold() == null;
    }

    /** 요약에 넣을 한 줄. */
    public String line() {
        if (!isSuccess()) {
            return "%s · 막힘%s".formatted(definition.getName(),
                    blockedDays > 0 ? " (%d일째)".formatted(blockedDays) : "");
        }
        StringBuilder line = new StringBuilder(definition.getName());
        if (alertable > 0) {
            line.append(" · 차이 ").append(alertable).append("건");
        } else {
            line.append(" · 차이 없음");
        }
        if (run.getUnmatchedCount() > 0) {
            line.append(" · 짝 없는 품목 ").append(run.getUnmatchedCount()).append("개");
        }
        if (hasNoThreshold()) {
            line.append(" · 알릴 기준 없음");
        }
        return line.toString();
    }
}
