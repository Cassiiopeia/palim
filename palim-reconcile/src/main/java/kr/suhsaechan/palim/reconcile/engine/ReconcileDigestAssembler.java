package kr.suhsaechan.palim.reconcile.engine;

import java.time.LocalDate;
import java.util.List;
import kr.suhsaechan.palim.notification.payload.ReconcileDigestPayload;
import org.springframework.stereotype.Component;

/**
 * 하루치 대조 결과를 <b>한 통으로</b> 접는다.
 *
 * <p>지금까지 통이 세 축으로 갈렸다 — 대조 정의마다, 「차이」 와 「막힘」 이 따로, 일일 리포트는
 * 또 다른 시각. 「하루 한 통」 은 셋을 다 접어야 되고, 그 유일한 자리가 <b>대조를 다 돌린
 * 뒤</b>다.
 *
 * <p><b>이상이 없어도 접어서 보낸다.</b> 지금까지 무음은 「오늘 깨끗함」·「전부 첫 관찰이라
 * 보류」·「알릴 기준을 안 정함」·「며칠째 막힘」·「보낼 곳이 연결 안 돼 쌓이는 중」 을 동시에
 * 뜻했다. 그 다섯을 가르지 못하면 「열지 않아도 판단」 이 성립하지 않는다.
 */
@Component
public class ReconcileDigestAssembler {

    /** 요약 본문에 늘어놓을 줄 수. 넘으면 스크롤이 되고, 스크롤되는 알림은 안 읽힌다. */
    private static final int LINE_LIMIT = 20;

    /**
     * 며칠째부터 <b>제목</b>에 올릴 것인가.
     *
     * <p>하루 못 돈 것은 다음 회차에 저절로 풀린다. 그것까지 제목에 올리면 수집이 늦은 날마다
     * 「막힘」 이 뜨고, 그러면 제목이 배경음이 되어 <b>정작 안 풀리는 막힘도 안 보인다.</b>
     *
     * <p>본문에는 <b>하루째부터 담는다.</b> 요약은 어차피 매일 오므로 담는 것이 소음이 되지
     * 않고, 담지 않으면 「오늘 대조가 안 돌았다」 는 사실이 통째로 사라진다.
     */
    private static final int DAYS_TO_HEADLINE = 3;

    public ReconcileDigestPayload assemble(LocalDate targetDate, List<DefinitionOutcome> outcomes) {
        // 제목에 올릴 것만 여기 담는다. 하루 이틀 막힌 것은 아래 본문 줄에만 나온다.
        List<ReconcileDigestPayload.Blocked> blocked = outcomes.stream()
                .filter(outcome -> !outcome.isSuccess())
                .filter(outcome -> outcome.blockedDays() >= DAYS_TO_HEADLINE)
                .map(outcome -> new ReconcileDigestPayload.Blocked(
                        outcome.definition().getName(), outcome.blockedDays()))
                .toList();

        int succeeded = (int) outcomes.stream().filter(DefinitionOutcome::isSuccess).count();
        int withDiff = (int) outcomes.stream().filter(o -> o.alertable() > 0).count();
        int diffCount = outcomes.stream().mapToInt(DefinitionOutcome::alertable).sum();
        // 짝을 못 찾은 품목은 지금까지 «영영 알림으로 나가지 않았다» — 상태가 늘 「관찰중」 이라
        // 알릴 대상이 아니었다. 견주는 범위가 조용히 줄어드는데 아무도 모르는 상태였다.
        int unmatched = outcomes.stream().filter(DefinitionOutcome::isSuccess)
                .mapToInt(o -> o.run().getUnmatchedCount()).sum();
        // 알릴 기준을 안 정한 대조도 마찬가지다. 차이가 아무리 나도 조용하다.
        int withoutThreshold = (int) outcomes.stream()
                .filter(DefinitionOutcome::hasNoThreshold).count();

        List<String> lines = outcomes.stream()
                .map(DefinitionOutcome::line)
                .limit(LINE_LIMIT)
                .toList();

        return new ReconcileDigestPayload(targetDate, outcomes.size(), succeeded, withDiff,
                diffCount, unmatched, withoutThreshold, blocked, lines);
    }
}
