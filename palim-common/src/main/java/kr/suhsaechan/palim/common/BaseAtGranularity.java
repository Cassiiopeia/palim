package kr.suhsaechan.palim.common;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 기준 시각의 <b>눈금</b>.
 *
 * <p>「이 재고는 언제 시점의 것인가」 를 어느 굵기로 볼지 정한다. 원천마다 실제 해상도가
 * 다르기 때문에 하나로 고정할 수 없다.
 *
 * <pre>
 * 이카운트  기준일을 날짜로만 받는다        →  하루보다 잘게 나눠도 의미가 없다
 * 물류      「지금 재고」 를 준다            →  부른 시각이 곧 기준 시각이다
 * </pre>
 *
 * <p><b>예전에는 하루로 못박혀 있었다.</b> 그래서 한 시간마다 수집하도록 시각을 정해 두면
 * 그날 것이 전부 같은 기준 시각으로 들어가 <b>서로 덮어썼다</b> — 자연키에 기준 시각이 들어
 * 있어서다. 오전 10시 재고를 나중에 볼 방법이 없었고, 덮였다는 사실도 어디에도 남지 않았다.
 *
 * <p>대조도 같은 눈금을 쓴다. 다만 <b>어느 쪽 모듈에도 속하지 않는다</b> — 수집과 대조가 서로
 * 의존하면 안 되고(02-ARCHITECTURE 「도메인 모듈끼리 직접 의존하지 않는다」), 둘이 같은 뜻을
 * 써야 하므로 공용 자리에 둔다.
 */
public enum BaseAtGranularity {

    /** 하루. 원천이 날짜만 주는 경우이며, 지금까지의 동작이다. */
    DAY("하루", Duration.ofDays(1)),

    /** 한 시간. 하루에 여러 번 받아 시간대별로 남기고 싶을 때. */
    HOUR("한 시간", Duration.ofHours(1)),

    /** 십 분. 촘촘히 받아 그 사이 움직임까지 보고 싶을 때. */
    TEN_MINUTES("십 분", Duration.ofMinutes(10)),

    /** 받은 그대로. 내림하지 않는다 — 두 원천을 정확히 같은 순간에 받을 수 있을 때만 쓴다. */
    EXACT("받은 그대로", Duration.ZERO);

    /**
     * 「하루」 를 가르는 지역.
     *
     * <p>{@code systemDefault()} 를 쓰면 <b>같은 코드가 다른 뜻이 된다</b> — CI 는 UTC 라
     * 자정이 우리 아침 9시이고, 운영 컨테이너는 서울이다. 「어제 것과 오늘 것」 이 갈리는
     * 자리가 환경마다 달라지면 테스트는 통과하는데 운영에서만 어긋난다.
     */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final String label;
    private final Duration size;

    BaseAtGranularity(String label, Duration size) {
        this.label = label;
        this.size = size;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 그 눈금의 시작점으로 내린다.
     *
     * <p>하루는 <b>그 지역의 자정</b>이어야 한다. UTC 로 자르면 우리 아침 9시가 전날로 넘어가,
     * 「어제 것과 오늘 것」 이 사람이 생각하는 날과 어긋난다.
     */
    public Instant truncate(Instant moment) {
        ZoneId zone = BUSINESS_ZONE;
        return switch (this) {
            case DAY -> moment.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
            case HOUR -> moment.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant();
            case TEN_MINUTES -> {
                var at = moment.atZone(zone).truncatedTo(ChronoUnit.MINUTES);
                yield at.minusMinutes(at.getMinute() % 10).toInstant();
            }
            case EXACT -> moment;
        };
    }

    /**
     * 이 눈금이 {@code other} 보다 <b>잘거나 같은가</b>.
     *
     * <p>견주는 눈금이 담는 눈금보다 잘면 두 원천이 같은 칸에 <b>영영 못 들어온다</b> — 하루에
     * 한 번 담는 쪽은 늘 자정에 찍히고, 시간 눈금으로 견주면 그 칸에 상대가 없다. 대조는
     * 매일 「기준 시각이 다릅니다」 만 남기고, 사람은 무엇이 잘못됐는지 알 수 없다.
     */
    public boolean isFinerThan(BaseAtGranularity other) {
        return size.compareTo(other.size) < 0;
    }
}
