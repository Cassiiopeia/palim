package kr.suhsaechan.palim.reconcile.engine;

import java.math.BigDecimal;
import java.util.List;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;
import org.springframework.stereotype.Component;

/**
 * 무엇을 알릴지 정한다.
 *
 * <p>알릴 것을 <b>고르는 일</b>과 <b>보내는 일</b>을 나눈다. 판단이 발송 코드에 섞이면 「왜
 * 이건 안 왔지」를 확인하려고 발송 경로를 뒤져야 하고, 그 판단은 테스트하기도 어렵다.
 *
 * <p>두 가지를 거른다.
 *
 * <ol>
 *   <li><b>관찰중은 알리지 않는다.</b> 반영 지연일 수 있고 다음 회차에 사라진다. 그것까지
 *       알리면 매일 헛알림이 가고, 그러면 진짜 알림도 안 보게 된다</li>
 *   <li><b>임계 미만은 알리지 않는다.</b> 낱개 몇 개 차이까지 알리면 알림이 잡음이 된다 —
 *       잡음이 되는 순간 그 알림은 없는 것과 같아진다</li>
 * </ol>
 */
@Component
public class ReconcileAlertPolicy {

    /**
     * 이번 실행에서 알릴 차이들.
     *
     * @param definition 임계가 비어 있으면 아무것도 알리지 않는다
     * @param diffs      이번 실행에서 나온 차이 전부
     */
    public List<ReconcileDiff> selectAlertable(ReconcileDefinition definition,
                                               List<ReconcileDiff> diffs) {
        BigDecimal threshold = definition.getAlertThreshold();
        if (threshold == null) {
            // 임계를 정하지 않았으면 알리지 않기로 한 것이다. 기본값을 임의로 정해 보내면
            // 사람이 «왜 이게 오지» 하고 알림 자체를 꺼 버린다.
            return List.of();
        }

        return diffs.stream()
                // 사람이 «알면서 둔다» 고 한 것도 여기서 빠진다 — CONFIRMED 만 통과하므로.
                .filter(ReconcileDiff::isConfirmed)
                .filter(diff -> diff.getDelta().abs().compareTo(threshold) >= 0)
                .toList();
    }
}
