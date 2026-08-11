package kr.suhsaechan.palim.automation.influencer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.automation.influencer.scoring.Grade;
import kr.suhsaechan.palim.automation.influencer.scoring.HardFailReason;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 캠페인×채널 점수. 재채점은 행을 늘리지 않고 갱신한다.
 *
 * <p>세부 배점을 컬럼이 아니라 {@code jsonb} 로 두는 이유는 캘리브레이션 때문이다 —
 * 발주사와 루브릭을 맞춰 가는 동안 항목 구성이 계속 바뀌는데, 컬럼으로 고정하면 조정 한 번에
 * 마이그레이션이 따라붙는다. 정렬·필터에 쓰는 총점·등급·CPV 만 컬럼으로 뺀다.
 *
 * <p>{@code inputHash} 는 AI 재현성 장치다. 영상 목록·댓글이 그대로면 같은 해시가 나오고,
 * 그때는 AI 를 다시 호출하지 않고 기존 점수를 유지한다.
 */
@Getter
@Entity
@Table(name = "influencer_score")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InfluencerScore extends BaseTimeEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    private InfluencerChannel channel;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal ruleTotal;

    /** AI 심층 심사 전에는 null 이다 — 화면은 "AI 미평가"로 표시한다. */
    @Column(precision = 6, scale = 2)
    private BigDecimal aiTotal;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 1)
    private Grade grade;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String ruleBreakdown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String aiBreakdown;

    /** 배지 목록을 콤마로 이어 붙인 값(예: {@code CRASH,TREND}). 없으면 빈 문자열. */
    @Column(nullable = false, length = 100)
    private String badges;

    /** 하드 탈락 사유. 값이 있으면 점수는 참고용이고 후보에서 제외된다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private HardFailReason hardFailReason;

    @Column(nullable = false)
    private long estimatedPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal estimatedCpv;

    /** 실제로 받은 견적. 입력되면 화면이 추정 단가 대신 이 값을 쓴다. */
    private Long quotedPrice;

    @Column(length = 64)
    private String inputHash;

    /** 채점에 쓴 루브릭 버전. 기준이 바뀐 뒤의 점수와 섞이지 않게 한다. */
    @Column(nullable = false, length = 20)
    private String rubricVersion;

    @Column(nullable = false)
    private Instant scoredAt;

    @Version
    private Long version;

    private InfluencerScore(Campaign campaign, InfluencerChannel channel, BigDecimal ruleTotal,
                            String ruleBreakdown, String badges, Grade grade, long estimatedPrice,
                            BigDecimal estimatedCpv, String rubricVersion, Instant scoredAt) {
        this.id = UuidV7.generate();
        this.campaign = campaign;
        this.channel = channel;
        this.ruleTotal = ruleTotal;
        this.total = ruleTotal;
        this.ruleBreakdown = ruleBreakdown;
        this.badges = badges;
        this.grade = grade;
        this.estimatedPrice = estimatedPrice;
        this.estimatedCpv = estimatedCpv;
        this.rubricVersion = rubricVersion;
        this.scoredAt = scoredAt;
    }

    /** 룰 채점 결과로 생성. AI 점수는 아직 없으므로 총점은 룰 점수와 같다. */
    public static InfluencerScore of(Campaign campaign, InfluencerChannel channel,
                                     BigDecimal ruleTotal, String ruleBreakdown, String badges,
                                     Grade grade, long estimatedPrice, BigDecimal estimatedCpv,
                                     String rubricVersion, Instant scoredAt) {
        return new InfluencerScore(campaign, channel, ruleTotal, ruleBreakdown, badges, grade,
                estimatedPrice, estimatedCpv, rubricVersion, scoredAt);
    }

    /** 재채점 — 룰 점수만 갱신하고 AI 결과는 유지한다. */
    public void updateRuleResult(BigDecimal ruleTotal, String ruleBreakdown, String badges,
                                 Grade grade, long estimatedPrice, BigDecimal estimatedCpv,
                                 Instant scoredAt) {
        this.ruleTotal = ruleTotal;
        this.ruleBreakdown = ruleBreakdown;
        this.badges = badges;
        this.estimatedPrice = estimatedPrice;
        this.estimatedCpv = estimatedCpv;
        this.total = aiTotal == null ? ruleTotal : ruleTotal.add(aiTotal);
        this.grade = grade;
        this.scoredAt = scoredAt;
        this.hardFailReason = null;
    }

    /** AI 심층 심사 결과 반영. 총점은 룰+AI 이며 등급은 호출자가 재계산해 넘긴다. */
    public void applyAiResult(BigDecimal aiTotal, String aiBreakdown, String inputHash, Grade grade,
                              Instant scoredAt) {
        this.aiTotal = aiTotal;
        this.aiBreakdown = aiBreakdown;
        this.inputHash = inputHash;
        this.total = ruleTotal.add(aiTotal);
        this.grade = grade;
        this.scoredAt = scoredAt;
    }

    public void markHardFail(HardFailReason reason) {
        this.hardFailReason = reason;
    }

    /** 실제 견적 입력 — 추정 CPV 를 실측값으로 대체한다. */
    public void overrideQuotedPrice(long quotedPrice) {
        this.quotedPrice = quotedPrice;
    }

    /** AI 재호출이 필요한지 — 입력이 그대로면 기존 점수를 유지한다(재현성). */
    public boolean needsAiReview(String currentInputHash) {
        return inputHash == null || !inputHash.equals(currentInputHash);
    }
}
