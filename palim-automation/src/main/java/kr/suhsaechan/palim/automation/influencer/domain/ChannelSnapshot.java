package kr.suhsaechan.palim.automation.influencer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채널 통계 일별 스냅샷 — 장기 성장 곡선의 원천.
 *
 * <p>모멘텀 점수는 영상 순서(최근 10편 대 직전 10편)로 계산하므로 스냅샷 없이도 첫 실행부터
 * 나온다. 이 테이블은 그것과 별개로 <b>구독자 증가 추이</b>를 보기 위한 것이다 — 조회수가
 * 먼저 터지고 구독자가 뒤따라 붙는 시차를 눈으로 확인하는 데 쓴다.
 *
 * <p>날짜 단위는 {@link LocalDate} 다. 이 값은 시각이 아니라 "며칠 자 관측치"라는 구분이며,
 * 유니크 제약({@code ux_channel_snapshot_channel_date})이 하루 한 행을 보장한다.
 */
@Getter
@Entity
@Table(name = "channel_snapshot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChannelSnapshot extends BaseTimeEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    private InfluencerChannel channel;

    @Column(nullable = false)
    private LocalDate capturedOn;

    @Column(nullable = false)
    private long subscriberCount;

    @Column(nullable = false)
    private long totalViewCount;

    @Column(nullable = false)
    private int videoCount;

    private ChannelSnapshot(InfluencerChannel channel, LocalDate capturedOn, long subscriberCount,
                            long totalViewCount, int videoCount) {
        this.id = UuidV7.generate();
        this.channel = channel;
        this.capturedOn = capturedOn;
        this.subscriberCount = subscriberCount;
        this.totalViewCount = totalViewCount;
        this.videoCount = videoCount;
    }

    public static ChannelSnapshot of(InfluencerChannel channel, LocalDate capturedOn,
                                     long subscriberCount, long totalViewCount, int videoCount) {
        return new ChannelSnapshot(channel, capturedOn, subscriberCount, totalViewCount, videoCount);
    }
}
