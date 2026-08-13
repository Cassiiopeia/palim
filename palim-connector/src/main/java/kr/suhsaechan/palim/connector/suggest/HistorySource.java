package kr.suhsaechan.palim.connector.suggest;

import kr.suhsaechan.palim.connector.model.FieldDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예전 기록 — 전에 이 이름을 어디에 연결했는지 본다.
 *
 * <p><b>네 근거 중 확장성의 핵심이다.</b> 사전은 우리가 아는 이름만 잡고, 다음에 붙일 시스템의
 * 칸 이름은 알 수 없다. 그런데 사람이 한 번 연결해 주면 그 뒤로는 우리가 몰라도 된다.
 *
 * <pre>
 *   처음   STOCK_BALANCE → 사람이 「수량」에 연결
 *          ↓ 기억한다
 *   다음   STOCK_BALANCE → 시스템이 먼저 골라 둔다
 * </pre>
 *
 * <p>사전보다 조금 낮게 시작하는 이유는 사람도 실수하기 때문이다. 한 번 잘못 연결한 것이
 * 사전만큼 확실해지면 그 실수가 굳는다. 대신 <b>자주 한 연결일수록</b> 점수를 올려 반복된
 * 판단에 무게를 준다.
 */
@Component
@RequiredArgsConstructor
public class HistorySource implements SuggestionSource {

    private static final int BASE_POINTS = 70;
    private static final int PER_HIT = 10;
    private static final int MAX_POINTS = 95;

    private final FieldMappingMemoryRepository memories;

    @Override
    @Transactional(readOnly = true)
    public Score score(Context context, FieldDefinition candidate) {
        return memories.findBySourceFieldAndTargetModelAndTargetField(
                        context.normalizedField(), context.targetModel(), candidate.key())
                .map(memory -> Score.of(points(memory.getHitCount()),
                        "전에 %d번 이렇게 연결하셨습니다".formatted(memory.getHitCount())))
                .orElse(Score.none());
    }

    /** 반복될수록 확신이 커진다. 다만 사전을 넘지는 않는다 — 사람도 실수하기 때문이다. */
    private static int points(int hitCount) {
        return Math.min(MAX_POINTS, BASE_POINTS + (hitCount - 1) * PER_HIT);
    }
}
