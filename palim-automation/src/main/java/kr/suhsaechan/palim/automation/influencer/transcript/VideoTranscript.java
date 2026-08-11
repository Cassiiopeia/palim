package kr.suhsaechan.palim.automation.influencer.transcript;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideo;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 영상 자막.
 *
 * <p>실패도 행으로 남긴다. 그래야 "자막이 없어서 신뢰도가 낮다"를 화면이 설명할 수 있고,
 * 실패한 영상을 매번 다시 시도해 차단을 자초하지 않는다.
 */
@Getter
@Entity
@Table(name = "video_transcript")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VideoTranscript extends BaseTimeEntity {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id", nullable = false)
    private InfluencerVideo video;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TranscriptStatus status;

    @Column(length = 10)
    private String language;

    @Column(columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private Instant fetchedAt;

    private VideoTranscript(InfluencerVideo video, TranscriptResult result, Instant fetchedAt) {
        this.id = UuidV7.generate();
        this.video = video;
        this.status = result.status();
        this.language = result.language();
        this.content = result.content();
        this.fetchedAt = fetchedAt;
    }

    public static VideoTranscript of(InfluencerVideo video, TranscriptResult result,
                                     Instant fetchedAt) {
        return new VideoTranscript(video, result, fetchedAt);
    }

    public void update(TranscriptResult result, Instant fetchedAt) {
        this.status = result.status();
        this.language = result.language();
        this.content = result.content();
        this.fetchedAt = fetchedAt;
    }

    public boolean hasContent() {
        return status == TranscriptStatus.OK && content != null && !content.isBlank();
    }
}
