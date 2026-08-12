package kr.suhsaechan.palim.automation.influencer.rising;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 라이징 감지 신호 — 채널당 한 행.
 *
 * <p>라이징은 <b>상태</b>이지 이력이 아니다. 재평가하면 갱신하고, 성장이 꺾이면 {@code active}
 * 만 내린다. 행을 지우지 않는 이유는 "한때 라이징이었는데 실제로 컸는가"가 나중에 지수의
 * 정확도를 대조할 근거가 되기 때문이다.
 *
 * <p>{@link #detectedAt} 은 최초 감지 시각이며 재평가로 갱신되지 않는다. 라이징은 유통기한이
 * 있는 정보라 <b>며칠째인지</b>가 판단에 직결된다 — 2주 지난 신호는 이미 단가가 올랐다는 뜻이다.
 */
@Getter
@Entity
@Table(name = "rising_signal")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RisingSignal extends BaseTimeEntity {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    private InfluencerChannel channel;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal total;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String breakdown;

    /** 조회수 중앙값 ÷ 구독자 기반 기대 조회수. 1.0 이면 규모에 맞는 평범한 성과다. */
    @Column(nullable = false, precision = 8, scale = 3)
    private BigDecimal arbitrageRatio;

    @Column(nullable = false)
    private long medianViews;

    @Column(nullable = false)
    private Instant detectedAt;

    @Column(nullable = false)
    private Instant evaluatedAt;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    private RisingSignal(InfluencerChannel channel, BigDecimal total, String breakdown,
                         BigDecimal arbitrageRatio, long medianViews, Instant detectedAt) {
        this.id = UuidV7.generate();
        this.channel = channel;
        this.total = total;
        this.breakdown = breakdown;
        this.arbitrageRatio = arbitrageRatio;
        this.medianViews = medianViews;
        this.detectedAt = detectedAt;
        this.evaluatedAt = detectedAt;
        this.active = true;
    }

    public static RisingSignal detect(InfluencerChannel channel, BigDecimal total, String breakdown,
                                      BigDecimal arbitrageRatio, long medianViews,
                                      Instant detectedAt) {
        return new RisingSignal(channel, total, breakdown, arbitrageRatio, medianViews, detectedAt);
    }

    /** 재평가 — 여전히 라이징이다. {@code detectedAt} 은 건드리지 않는다. */
    public void refresh(BigDecimal total, String breakdown, BigDecimal arbitrageRatio,
                        long medianViews, Instant evaluatedAt) {
        this.total = total;
        this.breakdown = breakdown;
        this.arbitrageRatio = arbitrageRatio;
        this.medianViews = medianViews;
        this.evaluatedAt = evaluatedAt;
        // 꺼졌다가 다시 감지되면 그 시점이 새로운 시작이다 — 이전 감지일을 쓰면
        // "3개월째 라이징"처럼 사실과 다른 표시가 나온다.
        if (!active) {
            this.detectedAt = evaluatedAt;
            this.active = true;
        }
    }

    /** 성장이 꺾였다. 레이더에서 내리되 기록은 남긴다. */
    public void deactivate(Instant evaluatedAt) {
        this.active = false;
        this.evaluatedAt = evaluatedAt;
    }

    /** 감지 후 경과일. 화면이 "3일째"를 보여주는 근거다. */
    public long daysSinceDetected(Instant now) {
        return Duration.between(detectedAt, now).toDays();
    }
}
