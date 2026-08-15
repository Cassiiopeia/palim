package kr.suhsaechan.palim.reconcile.engine;

import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.BaseAtGranularity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p><b>「정확히 같은 시각」 을 요구하지 않는다.</b> 원천마다 실제 해상도가 다르기 때문이다 —
 * 전산은 기준일을 날짜로만 받고, 물류는 「지금 재고」 를 준다. 그래서 <b>대조가 정한 눈금으로
 * 내려</b> 같은 칸에 들어오는지 본다. 물류를 한 시간마다 받아도 눈금이 하루면 「그날 것끼리」
 * 견주면 된다.
 *
 * <p>눈금을 여기서 찾지 않고 <b>불러 주는 쪽이 들고 온다.</b> 어느 원천이 얼마나 촘촘한지는
 * 연동이 알고, 이 모듈은 연동을 알지 않는다(02-ARCHITECTURE 「도메인 모듈끼리 직접 의존하지
 * 않는다」).
 *
 * <p>거부가 막다른 길이 아닌 이유는 <b>기준일을 지정해 다시 받아올 수 있기 때문</b>이다.
 * 사람이 할 일이 분명히 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaseAtResolver {

    private final SnapshotAggregator aggregator;

    /**
     * 견줄 두 시각.
     *
     * <p>둘이 같을 필요는 없다. 같은 <b>칸</b>에 들어오면 된다.
     *
     * @param bucket 두 원천이 함께 들어온 칸의 시작 시각. 실행 기록에 남는다
     * @param left   왼쪽 원천이 실제로 가진 시각
     * @param right  오른쪽 원천이 실제로 가진 시각
     */
    public record Aligned(Instant bucket, Instant left, Instant right) {
    }

    /**
     * @throws BusinessException 한쪽에 재고가 없거나({@code RECONCILE_SNAPSHOT_MISSING}),
     *                           같은 칸에 들어오지 않을 때({@code RECONCILE_BASE_AT_MISMATCH})
     */
    public Aligned resolve(UUID tenantId, String leftSource, String rightSource,
                           BaseAtGranularity granularity) {
        Instant left = aggregator.latestBaseAt(tenantId, leftSource)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RECONCILE_SNAPSHOT_MISSING, leftSource));
        Instant right = aggregator.latestBaseAt(tenantId, rightSource)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RECONCILE_SNAPSHOT_MISSING, rightSource));

        Instant leftBucket = granularity.truncate(left);
        Instant rightBucket = granularity.truncate(right);

        if (!leftBucket.equals(rightBucket)) {
            log.warn("기준 시각이 같은 칸에 없다 — 눈금={} 좌={}(칸 {}) 우={}(칸 {})",
                    granularity, left, leftBucket, right, rightBucket);
            throw new BusinessException(ErrorCode.RECONCILE_BASE_AT_MISMATCH, left, right);
        }
        log.debug("기준 시각 정렬 — 눈금={} 칸={} 좌={} 우={}",
                granularity, leftBucket, left, right);
        return new Aligned(leftBucket, left, right);
    }
}
