package kr.suhsaechan.palim.automation.influencer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
 * 발굴된 유튜브 채널.
 *
 * <p>발굴 경로가 여럿이라 같은 채널이 검색·차트·추천에서 중복으로 나온다.
 * {@code ux_influencer_channel_youtube_id} 가 한 행 보장의 최종 방어선이고,
 * {@link #register}로 만든 뒤 통계는 {@link #updateStatistics}로 덮어쓴다.
 *
 * <p>구독자 수는 여기에 저장하지만 점수에는 직접 반영되지 않는다 — 광고 단가가 구독자를
 * 따라가는 반면 성과는 조회수로 나오므로, 스코어링에서 구독자는 분모로만 쓴다.
 */
@Getter
@Entity
@Table(name = "influencer_channel")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InfluencerChannel extends BaseTimeEntity {

    @Id
    private UUID id;

    /** 유튜브 채널 ID(UC...). 외부 시스템과의 유일한 접점 식별자다. */
    @Column(nullable = false, length = 64)
    private String youtubeChannelId;

    @Column(nullable = false, length = 200)
    private String title;

    /** @handle 형식. 없는 채널도 있다. */
    @Column(length = 100)
    private String handle;

    /** ISO 3166-1 alpha-2. 국내 한정 선별에 쓴다. 미공개 채널은 null 이다. */
    @Column(length = 2)
    private String country;

    /** 업로드 재생목록 ID. 최근 영상 목록을 1 unit 으로 읽는 통로다. */
    @Column(length = 64)
    private String uploadsPlaylistId;

    @Column(nullable = false)
    private long subscriberCount;

    @Column(nullable = false)
    private long totalViewCount;

    @Column(nullable = false)
    private int videoCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DiscoverySource discoverySource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RefreshTier refreshTier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChannelStatus status;

    /** 사람이 제외한 사유. 심사 화면에서 왜 뺐는지 남긴다. */
    @Column(length = 500)
    private String exclusionNote;

    /** 마지막 지표 갱신 시각. 티어별 갱신 대상 선정의 기준이다. */
    private Instant lastRefreshedAt;

    @Version
    private Long version;

    private InfluencerChannel(String youtubeChannelId, String title, String handle, String country,
                              String uploadsPlaylistId, DiscoverySource discoverySource) {
        this.id = UuidV7.generate();
        this.youtubeChannelId = youtubeChannelId;
        this.title = title;
        this.handle = handle;
        this.country = country;
        this.uploadsPlaylistId = uploadsPlaylistId;
        this.discoverySource = discoverySource;
        this.refreshTier = RefreshTier.COLD;
        this.status = ChannelStatus.ACTIVE;
    }

    /** 발굴 직후 등록. 통계는 아직 0이며 수집 단계에서 채운다. */
    public static InfluencerChannel register(String youtubeChannelId, String title, String handle,
                                             String country, String uploadsPlaylistId,
                                             DiscoverySource discoverySource) {
        return new InfluencerChannel(youtubeChannelId, title, handle, country, uploadsPlaylistId,
                discoverySource);
    }

    /** 채널 통계 갱신. 값은 항상 최신으로 덮어쓰고, 추이는 스냅샷 테이블이 담당한다. */
    public void updateStatistics(long subscriberCount, long totalViewCount, int videoCount,
                                 Instant refreshedAt) {
        this.subscriberCount = subscriberCount;
        this.totalViewCount = totalViewCount;
        this.videoCount = videoCount;
        this.lastRefreshedAt = refreshedAt;
    }

    /** 제목·핸들은 채널 주인이 바꾸므로 갱신 때마다 반영한다. */
    public void updateProfile(String title, String handle, String country, String uploadsPlaylistId) {
        this.title = title;
        this.handle = handle;
        this.country = country;
        this.uploadsPlaylistId = uploadsPlaylistId;
    }

    public void changeTier(RefreshTier refreshTier) {
        this.refreshTier = refreshTier;
    }

    /** 사람이 후보에서 제외한다. 갱신 대상에서도 빠진다. */
    public void exclude(String note) {
        this.status = ChannelStatus.EXCLUDED;
        this.exclusionNote = note;
    }

    public void markDormant() {
        this.status = ChannelStatus.DORMANT;
        this.refreshTier = RefreshTier.COLD;
    }

    /** 휴면 채널이 다시 업로드했다. */
    public void reactivate() {
        this.status = ChannelStatus.ACTIVE;
    }
}
