package kr.suhsaechan.palim.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기준 시각의 눈금.
 *
 * <p>담긴 재고는 (원천, 기준 시각, 품목, 창고, 로트) 로 구분된다. 그래서 이 값이 <b>덮어쓸지
 * 따로 남길지를 혼자 결정한다.</b> 하루 눈금으로 하루에 두 번 담으면 나중 것이 앞엣것을 덮고,
 * 덮였다는 사실은 어디에도 남지 않는다 — 오전 재고를 다시 볼 방법이 없어진다.
 *
 * <p>그래서 이 시험은 「같은 칸에 들어가는가」 만 본다. 그것이 곧 덮어쓰기 여부다.
 */
class BaseAtGranularityTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private static Instant at(int hour, int minute) {
        return LocalDate.of(2026, 3, 5).atTime(hour, minute).atZone(BUSINESS_ZONE).toInstant();
    }

    @Test
    @DisplayName("하루 눈금은 같은 날 아침과 오후를 한 칸으로 본다")
    void 하루는_같은_날을_한_칸으로() {
        assertThat(BaseAtGranularity.DAY.truncate(at(9, 10)))
                .as("이래서 하루에 두 번 담으면 뒤엣것이 앞엣것을 덮는다")
                .isEqualTo(BaseAtGranularity.DAY.truncate(at(14, 30)));
    }

    @Test
    @DisplayName("시간 눈금은 아침과 오후를 다른 칸으로 남긴다")
    void 시간은_따로_남긴다() {
        assertThat(BaseAtGranularity.HOUR.truncate(at(9, 10)))
                .as("촘촘히 받아도 서로 덮지 않으려면 이것이 달라야 한다")
                .isNotEqualTo(BaseAtGranularity.HOUR.truncate(at(14, 30)));
        assertThat(BaseAtGranularity.HOUR.truncate(at(9, 10)))
                .as("같은 시간대끼리는 여전히 한 칸이다")
                .isEqualTo(BaseAtGranularity.HOUR.truncate(at(9, 55)));
    }

    @Test
    @DisplayName("십 분 눈금은 십 분 경계로 내린다")
    void 십_분_경계() {
        assertThat(BaseAtGranularity.TEN_MINUTES.truncate(at(9, 17)))
                .isEqualTo(at(9, 10));
    }

    /** 내림하지 않는다는 뜻이다. 두 원천을 정확히 같은 순간에 받을 수 있을 때만 쓸 값이다. */
    @Test
    @DisplayName("받은 그대로는 손대지 않는다")
    void 그대로는_손대지_않는다() {
        Instant moment = at(9, 17).plusMillis(432);
        assertThat(BaseAtGranularity.EXACT.truncate(moment)).isEqualTo(moment);
    }

    /**
     * <b>하루는 우리 자정이어야 한다.</b>
     *
     * <p>UTC 로 자르면 우리 아침 9시가 전날 칸으로 넘어간다. 그러면 「어제 것과 오늘 것」 이
     * 사람이 생각하는 날과 어긋나고, 아침에 담은 재고가 어제 것을 덮는다. 지역을 코드 밖에서
     * 정하게 두면(예: 실행 환경의 기본 지역) CI 는 UTC, 운영은 서울이라 <b>같은 코드가 다른
     * 뜻</b>이 된다 — 테스트는 통과하는데 운영에서만 어긋난다.
     */
    @Test
    @DisplayName("하루 경계는 UTC 가 아니라 우리 자정이다")
    void 하루_경계는_우리_자정() {
        assertThat(BaseAtGranularity.DAY.truncate(at(9, 0)))
                .isEqualTo(LocalDate.of(2026, 3, 5).atStartOfDay(BUSINESS_ZONE).toInstant());
    }

    /**
     * 견줄 눈금이 담는 눈금보다 잘면 두 원천은 <b>같은 칸에 영영 못 들어온다.</b> 하루에 한 번
     * 담는 쪽은 늘 자정에 찍히기 때문이다. 그런 대조는 매일 「기준 시각이 다릅니다」 만 남긴다.
     */
    @Test
    @DisplayName("잘고 굵음을 비교할 수 있다")
    void 잘고_굵음() {
        assertThat(BaseAtGranularity.HOUR.isFinerThan(BaseAtGranularity.DAY)).isTrue();
        assertThat(BaseAtGranularity.DAY.isFinerThan(BaseAtGranularity.HOUR)).isFalse();
        assertThat(BaseAtGranularity.DAY.isFinerThan(BaseAtGranularity.DAY)).isFalse();
        assertThat(BaseAtGranularity.EXACT.isFinerThan(BaseAtGranularity.TEN_MINUTES))
                .as("«받은 그대로» 가 가장 잘다 — 내림을 아예 하지 않는다")
                .isTrue();
    }
}
