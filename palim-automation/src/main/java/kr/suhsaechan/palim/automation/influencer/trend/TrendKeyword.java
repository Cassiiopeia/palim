package kr.suhsaechan.palim.automation.influencer.trend;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주간 키워드 빈도.
 *
 * <p>전주 빈도를 함께 저장한다. 증가율은 매번 조인해 계산할 수도 있지만, 보드가 열릴 때마다
 * 자기 조인을 도는 것보다 집계 시점에 한 번 박아 두는 편이 단순하고 빠르다.
 */
@Getter
@Entity
@Table(name = "trend_keyword")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrendKeyword extends BaseTimeEntity {

    /** 카테고리 구분 없는 전체 집계에 쓰는 코드. */
    public static final String ALL_CATEGORIES = "_all";

    @Id
    private UUID id;

    /** 주의 시작일(월요일). */
    @Column(nullable = false)
    private LocalDate weekStart;

    @Column(nullable = false, length = 50)
    private String categoryCode;

    @Column(nullable = false, length = 100)
    private String keyword;

    @Column(nullable = false)
    private int frequency;

    @Column(nullable = false)
    private int prevFrequency;

    private TrendKeyword(LocalDate weekStart, String categoryCode, String keyword,
                         int frequency, int prevFrequency) {
        this.id = UuidV7.generate();
        this.weekStart = weekStart;
        this.categoryCode = categoryCode;
        this.keyword = keyword;
        this.frequency = frequency;
        this.prevFrequency = prevFrequency;
    }

    public static TrendKeyword of(LocalDate weekStart, String categoryCode, String keyword,
                                  int frequency, int prevFrequency) {
        return new TrendKeyword(weekStart, categoryCode, keyword, frequency, prevFrequency);
    }

    public void update(int frequency, int prevFrequency) {
        this.frequency = frequency;
        this.prevFrequency = prevFrequency;
    }

    /**
     * 증가 배율.
     *
     * <p>전주에 없던 키워드는 나눗셈이 성립하지 않는다. 이때 큰 값을 주면 <b>한 번 나온 신조어가
     * 항상 1위</b>가 되므로, 신규는 별도로 표시하고 배율은 빈도 자체로 대체한다.
     */
    public double growthRatio() {
        return prevFrequency == 0 ? frequency : (double) frequency / prevFrequency;
    }

    /** 전주에 없던 키워드 — 새로 등장한 말이다. */
    public boolean isNew() {
        return prevFrequency == 0;
    }
}
