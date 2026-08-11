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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채널 분류 라벨. 채널 하나가 여러 카테고리를 가질 수 있다(다중 라벨).
 *
 * <p>{@link CategoryTaxonomy#YOUTUBE} 원본과 {@link CategoryTaxonomy#PALIM} 자체 분류를
 * 같은 테이블에 나란히 둔다. 분류기를 개선해 재분류할 때 원본이 남아 있어야 무엇이 어떻게
 * 바뀌었는지 대조할 수 있다.
 */
@Getter
@Entity
@Table(name = "channel_category")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChannelCategory extends BaseTimeEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    private InfluencerChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CategoryTaxonomy taxonomy;

    @Column(nullable = false, length = 50)
    private String categoryCode;

    /** AI 라벨의 확신도(0.000~1.000). API 원본은 null 이다 — 추론이 아니라 확정값이라서. */
    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LabelSource labelSource;

    @Column(nullable = false)
    private Instant labeledAt;

    private ChannelCategory(InfluencerChannel channel, CategoryTaxonomy taxonomy,
                            String categoryCode, BigDecimal confidence, LabelSource labelSource,
                            Instant labeledAt) {
        this.id = UuidV7.generate();
        this.channel = channel;
        this.taxonomy = taxonomy;
        this.categoryCode = categoryCode;
        this.confidence = confidence;
        this.labelSource = labelSource;
        this.labeledAt = labeledAt;
    }

    public static ChannelCategory of(InfluencerChannel channel, CategoryTaxonomy taxonomy,
                                     String categoryCode, BigDecimal confidence,
                                     LabelSource labelSource, Instant labeledAt) {
        return new ChannelCategory(channel, taxonomy, categoryCode, confidence, labelSource,
                labeledAt);
    }

    /** 재분류 — 같은 코드에 확신도만 갱신한다(유니크 제약상 행이 늘지 않는다). */
    public void relabel(BigDecimal confidence, Instant labeledAt) {
        this.confidence = confidence;
        this.labeledAt = labeledAt;
    }
}
