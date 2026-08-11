package kr.suhsaechan.palim.automation.influencer.discover;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.automation.influencer.domain.DiscoverySource;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 발굴 진행 위치.
 *
 * <p>할당량이 하루 예산으로 묶여 있어 발굴은 항상 중간에 끊긴다. 커서가 없으면 매번 첫 키워드부터
 * 다시 돌아 <b>목록 뒤쪽 키워드는 영원히 순서를 못 받는다.</b>
 *
 * @see #cursorKey 키워드 검색이면 키워드, 인기 차트면 카테고리 ID, 추천 채널 확장이면 채널 ID
 */
@Getter
@Entity
@Table(name = "discovery_cursor")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiscoveryCursor extends BaseTimeEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DiscoverySource source;

    @Column(nullable = false, length = 200)
    private String cursorKey;

    /** null 이면 그 키의 순회가 끝났다 — 다음 실행에서 처음부터 다시 돈다. */
    @Column(length = 500)
    private String pageToken;

    private Instant lastRunAt;

    /** 이 키로 발견한 신규 채널 누적 수. 성과 없는 키워드를 걷어내는 근거다. */
    @Column(nullable = false)
    private int foundCount;

    private DiscoveryCursor(DiscoverySource source, String cursorKey) {
        this.id = UuidV7.generate();
        this.source = source;
        this.cursorKey = cursorKey;
    }

    public static DiscoveryCursor of(DiscoverySource source, String cursorKey) {
        return new DiscoveryCursor(source, cursorKey);
    }

    /** 한 번 순회한 뒤 진행 상태를 기록한다. */
    public void advance(String nextPageToken, int newlyFound, Instant runAt) {
        this.pageToken = nextPageToken;
        this.foundCount += newlyFound;
        this.lastRunAt = runAt;
    }
}
