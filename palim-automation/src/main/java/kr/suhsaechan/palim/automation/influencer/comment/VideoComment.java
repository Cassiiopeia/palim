package kr.suhsaechan.palim.automation.influencer.comment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideo;
import kr.suhsaechan.palim.automation.influencer.youtube.CommentOrder;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 수집한 댓글.
 *
 * <p><b>작성자 정보가 없다.</b> 핸들·프로필·채널 ID 는 개인 식별자이므로 수집 단계에서 버린다 —
 * 저장하지 않는 것만으로는 부족하고, 매핑에서 버려야 AI 전송 경로로 새어 나가지 않는다.
 *
 * <p>{@link CommentOrder#TIME} 과 {@link CommentOrder#RELEVANCE} 를 둘 다 모은다. 최신순은
 * <b>지금 벌어지는 일</b>을 보여준다 — 논란이 터지면 몇 시간 안에 최신 댓글로 몰리므로 브랜드
 * 안전성 탐지의 핵심 신호다.
 */
@Getter
@Entity
@Table(name = "video_comment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VideoComment extends BaseTimeEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id", nullable = false)
    private InfluencerVideo video;

    @Enumerated(EnumType.STRING)
    @Column(name = "sort_source", nullable = false, length = 20)
    private CommentOrder sortSource;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private long likeCount;

    @Column(nullable = false)
    private Instant publishedAt;

    @Column(nullable = false)
    private Instant collectedAt;

    private VideoComment(InfluencerVideo video, CommentOrder sortSource, String content,
                         long likeCount, Instant publishedAt, Instant collectedAt) {
        this.id = UuidV7.generate();
        this.video = video;
        this.sortSource = sortSource;
        this.content = content;
        this.likeCount = likeCount;
        this.publishedAt = publishedAt;
        this.collectedAt = collectedAt;
    }

    public static VideoComment of(InfluencerVideo video, CommentOrder sortSource, String content,
                                  long likeCount, Instant publishedAt, Instant collectedAt) {
        return new VideoComment(video, sortSource, content, likeCount, publishedAt, collectedAt);
    }
}
