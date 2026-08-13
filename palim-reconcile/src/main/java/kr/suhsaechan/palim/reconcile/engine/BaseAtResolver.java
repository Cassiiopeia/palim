package kr.suhsaechan.palim.reconcile.engine;

import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 두 원천이 <b>같은 시각의 재고</b>를 갖고 있는지 확인한다.
 *
 * <p>두 재고를 다른 시각에 뽑으면 그 사이 출고분만큼 <b>무조건</b> 차이가 난다. 억지로 맞춰
 * 비교하면 그 차이가 진짜인지 시간 탓인지 영영 알 수 없다.
 *
 * <p>그런 결과는 몇 번 어긋나는 순간 아무도 보지 않게 된다. <b>대조가 신뢰를 잃는 것이 대조가
 * 없는 것보다 나쁘다</b> — 있는데 아무도 안 보는 화면이 되면, 문제가 있다는 사실 자체가 가려진다.
 *
 * <p>거부가 막다른 길이 아닌 이유는 <b>기준일을 지정해 다시 받아올 수 있기 때문</b>이다.
 * 사람이 할 일이 분명히 있다.
 */
@Component
@RequiredArgsConstructor
public class BaseAtResolver {

    private final SnapshotAggregator aggregator;

    /**
     * 양쪽이 공유하는 기준 시각.
     *
     * @throws BusinessException 한쪽에 재고가 없거나({@code RECONCILE_SNAPSHOT_MISSING}),
     *                           두 시각이 다를 때({@code RECONCILE_BASE_AT_MISMATCH})
     */
    public Instant resolve(UUID tenantId, String leftSource, String rightSource) {
        Instant left = aggregator.latestBaseAt(tenantId, leftSource)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RECONCILE_SNAPSHOT_MISSING, leftSource));
        Instant right = aggregator.latestBaseAt(tenantId, rightSource)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RECONCILE_SNAPSHOT_MISSING, rightSource));

        if (!left.equals(right)) {
            throw new BusinessException(ErrorCode.RECONCILE_BASE_AT_MISMATCH, left, right);
        }
        return left;
    }
}
