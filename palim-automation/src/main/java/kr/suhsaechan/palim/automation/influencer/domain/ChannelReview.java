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
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사람이 내린 심사 판정.
 *
 * <p>캠페인×채널 하나. 판단이 바뀌면 갱신이지 새 행이 아니다 — "결국 어떻게 결정했는지"가
 * 중요하고, 번복 과정은 감사 로그가 남긴다.
 */
@Getter
@Entity
@Table(name = "channel_review")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChannelReview extends BaseTimeEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    private InfluencerChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewDecision decision;

    /** 왜 그렇게 판단했는지. 나중에 루브릭을 손볼 때 가장 중요한 자료다. */
    @Column(length = 1000)
    private String note;

    @Column(nullable = false, length = 50)
    private String reviewer;

    @Column(nullable = false)
    private Instant decidedAt;

    @Version
    private Long version;

    private ChannelReview(Campaign campaign, InfluencerChannel channel, ReviewDecision decision,
                          String note, String reviewer, Instant decidedAt) {
        this.id = UuidV7.generate();
        this.campaign = campaign;
        this.channel = channel;
        this.decision = decision;
        this.note = note;
        this.reviewer = reviewer;
        this.decidedAt = decidedAt;
    }

    public static ChannelReview of(Campaign campaign, InfluencerChannel channel,
                                   ReviewDecision decision, String note, String reviewer,
                                   Instant decidedAt) {
        return new ChannelReview(campaign, channel, decision, note, reviewer, decidedAt);
    }

    public void change(ReviewDecision decision, String note, String reviewer, Instant decidedAt) {
        this.decision = decision;
        this.note = note;
        this.reviewer = reviewer;
        this.decidedAt = decidedAt;
    }
}
