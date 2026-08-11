package kr.suhsaechan.palim.automation.influencer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.automation.influencer.scoring.VideoSample;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채널의 개별 영상 지표.
 *
 * <p><b>쇼츠 분리가 이 엔티티의 핵심 책임이다.</b> 쇼츠는 조회수가 롱폼과 자릿수가 다르게
 * 잡히고 광고 전환은 낮아서, 섞어서 집계하면 참여율·도달 효율·안정성이 모두 무의미해진다.
 * 저장 시점에 {@code shortForm} 을 확정해 두면 이후 모든 조회가 이 플래그로 걸러진다.
 */
@Getter
@Entity
@Table(name = "influencer_video")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InfluencerVideo extends BaseTimeEntity {

    /** 쇼츠 판정 경계(초). YouTube 의 쇼츠 정의와 맞춘 고정값이다. */
    public static final int SHORT_FORM_MAX_SECONDS = 60;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    private InfluencerChannel channel;

    @Column(nullable = false, length = 32)
    private String youtubeVideoId;

    @Column(length = 300)
    private String title;

    @Column(nullable = false)
    private Instant publishedAt;

    @Column(nullable = false)
    private int durationSeconds;

    /** {@code duration <= 60초}. 컬럼명은 {@code short_form} — {@code short} 는 예약어다. */
    @Column(nullable = false)
    private boolean shortForm;

    @Column(nullable = false)
    private long viewCount;

    @Column(nullable = false)
    private long likeCount;

    @Column(nullable = false)
    private long commentCount;

    /** 유료 광고 표시 여부. 광고 포화도·미개척 판정의 근거다. */
    @Column(nullable = false)
    private boolean paidPromotion;

    /** 지표를 읽어온 시각. 조회수는 계속 오르므로 언제 기준인지가 중요하다. */
    @Column(nullable = false)
    private Instant capturedAt;

    private InfluencerVideo(InfluencerChannel channel, String youtubeVideoId, String title,
                            Instant publishedAt, int durationSeconds, long viewCount,
                            long likeCount, long commentCount, boolean paidPromotion,
                            Instant capturedAt) {
        this.id = UuidV7.generate();
        this.channel = channel;
        this.youtubeVideoId = youtubeVideoId;
        this.title = title;
        this.publishedAt = publishedAt;
        this.durationSeconds = durationSeconds;
        this.shortForm = durationSeconds <= SHORT_FORM_MAX_SECONDS;
        this.viewCount = viewCount;
        this.likeCount = likeCount;
        this.commentCount = commentCount;
        this.paidPromotion = paidPromotion;
        this.capturedAt = capturedAt;
    }

    public static InfluencerVideo of(InfluencerChannel channel, String youtubeVideoId, String title,
                                     Instant publishedAt, int durationSeconds, long viewCount,
                                     long likeCount, long commentCount, boolean paidPromotion,
                                     Instant capturedAt) {
        return new InfluencerVideo(channel, youtubeVideoId, title, publishedAt, durationSeconds,
                viewCount, likeCount, commentCount, paidPromotion, capturedAt);
    }

    /** 재수집 시 지표만 갱신한다. 길이·게시일은 바뀌지 않는다. */
    public void updateStatistics(long viewCount, long likeCount, long commentCount,
                                 boolean paidPromotion, Instant capturedAt) {
        this.viewCount = viewCount;
        this.likeCount = likeCount;
        this.commentCount = commentCount;
        this.paidPromotion = paidPromotion;
        this.capturedAt = capturedAt;
    }

    /** 스코어링 입력으로 변환. 도메인과 계산 엔진의 경계다. */
    public VideoSample toSample() {
        return new VideoSample(youtubeVideoId, publishedAt, durationSeconds, viewCount, likeCount,
                commentCount, paidPromotion);
    }
}
