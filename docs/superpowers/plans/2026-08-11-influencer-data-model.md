# 인플루언서 데이터 모델 구현 계획 (Plan 2/5)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** 인플루언서 채널·영상·스냅샷·캠페인·분류·점수를 담는 스키마와 JPA 엔티티를 만든다.

**Architecture:** `palim-automation` 에 `influencer.domain` 패키지 신설. Flyway `V5__influencer.sql` 로 스키마를 만들고 엔티티가 이를 그대로 매핑한다(`ddl-auto=validate` 가 통합 테스트에서 검증). 점수 세부는 캘리브레이션 중 구조가 바뀌므로 `jsonb` 문자열로 보관하고 총점·등급만 컬럼으로 뺀다.

**Tech Stack:** Java 25 / Spring Boot 4.x, Hibernate(`@JdbcTypeCode(SqlTypes.JSON)`), Flyway, Testcontainers PostgreSQL.

## Global Constraints

Plan 1 과 동일 — 이슈 #41, 커밋 컨벤션 `인플루언서 등급표, 라이징 레이더 설계 및 구현 : {타입} : {설명} {URL}`, AI 흔적 금지, 시각 `Instant`/`timestamptz`, 새 예외 클래스 금지, 테스트 데이터는 합성만, 로컬 Gradle 차단 시 CI 로 검증.

## 범위에서 뺀 테이블 (필요한 Plan 에서 추가)

`video_comment`·`video_transcript`(Plan 4 AI 심사) · `rising_signal`·`trend_keyword`(Plan 5) ·
`discovery_cursor`·`api_quota_ledger`(Plan 3 배치) · `channel_review`(Plan 5 심사 화면).
지금 만들면 쓰지 않는 빈 테이블이 된다.

---

### Task 1: Flyway V5 스키마

**Files:**
- Create: `palim-app/src/main/resources/db/migration/V5__influencer.sql`

**Interfaces:**
- Produces: 테이블 `influencer_channel` · `influencer_video` · `channel_snapshot` · `campaign` · `channel_category` · `influencer_score`. Task 2~4 의 엔티티가 이 컬럼명을 그대로 매핑한다.

- [ ] **Step 1: DDL 작성**

```sql
-- ============================================================
-- 인플루언서 등급표 · 라이징 레이더 (#41)
-- 유튜브 공식 API 지표 기반 채널 발굴·평가. 설계는
-- docs/superpowers/specs/2026-08-11-influencer-grading-design.md
-- ============================================================

CREATE TABLE influencer_channel
(
    id                   uuid         NOT NULL,
    youtube_channel_id   varchar(64)  NOT NULL,
    title                varchar(200) NOT NULL,
    handle               varchar(100),
    country              varchar(2),
    uploads_playlist_id  varchar(64),
    subscriber_count     bigint       NOT NULL,
    total_view_count     bigint       NOT NULL,
    video_count          integer      NOT NULL,
    discovery_source     varchar(30)  NOT NULL,
    refresh_tier         varchar(10)  NOT NULL,
    status               varchar(20)  NOT NULL,
    exclusion_note       varchar(500),
    last_refreshed_at    timestamptz,
    version              bigint,
    created_at           timestamptz,
    updated_at           timestamptz,
    CONSTRAINT pk_influencer_channel PRIMARY KEY (id)
);

-- 같은 채널을 두 경로(검색·차트·추천)로 발견해도 한 행이어야 한다.
CREATE UNIQUE INDEX ux_influencer_channel_youtube_id ON influencer_channel (youtube_channel_id);

-- 야간 배치가 "티어별로 갱신 기한이 지난 채널"을 읽는다.
CREATE INDEX ix_influencer_channel_tier_refreshed
    ON influencer_channel (refresh_tier, last_refreshed_at) WHERE status = 'ACTIVE';

CREATE TABLE influencer_video
(
    id               uuid        NOT NULL,
    channel_id       uuid        NOT NULL,
    youtube_video_id varchar(32) NOT NULL,
    title            varchar(300),
    published_at     timestamptz NOT NULL,
    duration_seconds integer     NOT NULL,
    short_form       boolean     NOT NULL,
    view_count       bigint      NOT NULL,
    like_count       bigint      NOT NULL,
    comment_count    bigint      NOT NULL,
    paid_promotion   boolean     NOT NULL,
    captured_at      timestamptz NOT NULL,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_influencer_video PRIMARY KEY (id),
    CONSTRAINT fk_influencer_video_channel FOREIGN KEY (channel_id)
        REFERENCES influencer_channel (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_influencer_video_youtube_id ON influencer_video (youtube_video_id);

-- 지표 계산이 "채널의 최근 롱폼 N개"를 읽는다.
CREATE INDEX ix_influencer_video_channel_published
    ON influencer_video (channel_id, published_at DESC);

CREATE TABLE channel_snapshot
(
    id               uuid   NOT NULL,
    channel_id       uuid   NOT NULL,
    captured_on      date   NOT NULL,
    subscriber_count bigint NOT NULL,
    total_view_count bigint NOT NULL,
    video_count      integer NOT NULL,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_channel_snapshot PRIMARY KEY (id),
    CONSTRAINT fk_channel_snapshot_channel FOREIGN KEY (channel_id)
        REFERENCES influencer_channel (id) ON DELETE CASCADE
);

-- 하루 한 행. 배치가 여러 번 돌아도 성장 곡선이 왜곡되지 않는다.
CREATE UNIQUE INDEX ux_channel_snapshot_channel_date ON channel_snapshot (channel_id, captured_on);

CREATE TABLE campaign
(
    id               uuid         NOT NULL,
    name             varchar(200) NOT NULL,
    product_category varchar(100),
    target_audience  varchar(500),
    selling_points   text,
    exclusions       text,
    target_reach_min bigint       NOT NULL,
    target_reach_max bigint       NOT NULL,
    subscriber_min   bigint       NOT NULL,
    subscriber_max   bigint       NOT NULL,
    status           varchar(20)  NOT NULL,
    version          bigint,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_campaign PRIMARY KEY (id)
);

CREATE TABLE channel_category
(
    id            uuid        NOT NULL,
    channel_id    uuid        NOT NULL,
    taxonomy      varchar(20) NOT NULL,
    category_code varchar(50) NOT NULL,
    confidence    numeric(4, 3),
    label_source  varchar(10) NOT NULL,
    labeled_at    timestamptz NOT NULL,
    created_at    timestamptz,
    updated_at    timestamptz,
    CONSTRAINT pk_channel_category PRIMARY KEY (id),
    CONSTRAINT fk_channel_category_channel FOREIGN KEY (channel_id)
        REFERENCES influencer_channel (id) ON DELETE CASCADE
);

-- 같은 분류체계의 같은 코드는 채널당 한 번만. 재분류는 갱신이지 추가가 아니다.
CREATE UNIQUE INDEX ux_channel_category_unique
    ON channel_category (channel_id, taxonomy, category_code);

CREATE INDEX ix_channel_category_lookup ON channel_category (taxonomy, category_code);

CREATE TABLE influencer_score
(
    id               uuid          NOT NULL,
    campaign_id      uuid          NOT NULL,
    channel_id       uuid          NOT NULL,
    rule_total       numeric(6, 2) NOT NULL,
    ai_total         numeric(6, 2),
    total            numeric(6, 2) NOT NULL,
    grade            varchar(1)    NOT NULL,
    rule_breakdown   jsonb         NOT NULL,
    ai_breakdown     jsonb,
    badges           varchar(100)  NOT NULL,
    hard_fail_reason varchar(30),
    estimated_price  bigint        NOT NULL,
    estimated_cpv    numeric(12, 2) NOT NULL,
    quoted_price     bigint,
    input_hash       varchar(64),
    rubric_version   varchar(20)   NOT NULL,
    scored_at        timestamptz   NOT NULL,
    version          bigint,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_influencer_score PRIMARY KEY (id),
    CONSTRAINT fk_influencer_score_campaign FOREIGN KEY (campaign_id)
        REFERENCES campaign (id) ON DELETE CASCADE,
    CONSTRAINT fk_influencer_score_channel FOREIGN KEY (channel_id)
        REFERENCES influencer_channel (id) ON DELETE CASCADE
);

-- 캠페인×채널 최신 점수 한 행. 재채점은 갱신이다.
CREATE UNIQUE INDEX ux_influencer_score_campaign_channel
    ON influencer_score (campaign_id, channel_id);

-- 등급표 기본 정렬(총점순)과 CPV 효율 정렬을 모두 받는다.
CREATE INDEX ix_influencer_score_campaign_total ON influencer_score (campaign_id, total DESC);
CREATE INDEX ix_influencer_score_campaign_cpv ON influencer_score (campaign_id, estimated_cpv);
```

- [ ] **Step 2: Commit**

```bash
git add palim-app/src/main/resources/db/migration/V5__influencer.sql
git commit -m "인플루언서 등급표, 라이징 레이더 설계 및 구현 : feat : 인플루언서 스키마 마이그레이션 추가 https://github.com/Cassiiopeia/palim/issues/41"
```

---

### Task 2: 열거형 + 채널·영상·스냅샷 엔티티

**Files:**
- Create: `.../influencer/domain/DiscoverySource.java` · `RefreshTier.java` · `ChannelStatus.java`
- Create: `.../influencer/domain/InfluencerChannel.java` · `InfluencerVideo.java` · `ChannelSnapshot.java`
- Create: 각 `...Repository.java`

**Interfaces:**
- Produces:
  - `enum DiscoverySource { KEYWORD_SEARCH, POPULAR_CHART, FEATURED_CHANNEL, MANUAL_SEED }`
  - `enum RefreshTier { HOT, WARM, COLD, RISING }`
  - `enum ChannelStatus { ACTIVE, EXCLUDED, DORMANT }`
  - `InfluencerChannel.register(youtubeChannelId, title, handle, country, uploadsPlaylistId, source)` → 신규 채널(구독자 0, tier COLD, status ACTIVE)
  - `channel.updateStatistics(subscriberCount, totalViewCount, videoCount, refreshedAt)`
  - `channel.changeTier(RefreshTier)` · `channel.exclude(note)` · `channel.markDormant()`
  - `InfluencerVideo.of(channel, youtubeVideoId, title, publishedAt, durationSeconds, viewCount, likeCount, commentCount, paidPromotion, capturedAt)` — `shortForm` 은 durationSeconds ≤ 60 으로 자동 판정
  - `video.updateStatistics(viewCount, likeCount, commentCount, capturedAt)`
  - `ChannelSnapshot.of(channel, capturedOn, subscriberCount, totalViewCount, videoCount)`
  - Repository: `findByYoutubeChannelId`, `findByYoutubeVideoId`, `findByChannelIdOrderByPublishedAtDesc`, `existsByChannelIdAndCapturedOn`

- [ ] **Step 1~N**: 아래 "구현 규칙"대로 작성하고 Task 4 통합 테스트로 검증한 뒤 커밋한다.

**구현 규칙 (모든 엔티티 공통):**
- `@Entity @Table(name="...") @Getter @NoArgsConstructor(access = PROTECTED)`, `extends BaseTimeEntity`
- `@Id private UUID id;` — 생성자에서 `UuidV7.generate()`
- 열거형 컬럼은 `@Enumerated(EnumType.STRING)`
- 정적 팩토리는 `of()`/`register()` — record 아님(엔티티)
- 연관은 `@ManyToOne(fetch = LAZY)` + `@JoinColumn(name="channel_id")` (같은 모듈 내부이므로 FK 허용, 모듈 간에는 UUID 값 참조 원칙 유지)
- `short` 은 Java 예약어라 컬럼 `short_form` ↔ 필드 `shortForm`

- [ ] **Commit**

```bash
git add palim-automation/
git commit -m "인플루언서 등급표, 라이징 레이더 설계 및 구현 : feat : 채널·영상·스냅샷 엔티티 추가 https://github.com/Cassiiopeia/palim/issues/41"
```

---

### Task 3: 캠페인 · 분류 · 점수 엔티티

**Files:**
- Create: `.../influencer/domain/CampaignStatus.java` · `CategoryTaxonomy.java` · `LabelSource.java`
- Create: `.../influencer/domain/Campaign.java` · `ChannelCategory.java` · `InfluencerScore.java`
- Create: 각 `...Repository.java`

**Interfaces:**
- Produces:
  - `enum CampaignStatus { DRAFT, ACTIVE, CLOSED }`
  - `enum CategoryTaxonomy { YOUTUBE, PALIM }` · `enum LabelSource { API, AI }`
  - `Campaign.of(name, productCategory, targetAudience, sellingPoints, exclusions, targetReachMin, targetReachMax, subscriberMin, subscriberMax)`
  - `campaign.toTarget()` → `CampaignTarget` (Plan 1 의 스코어링 입력으로 직접 변환)
  - `ChannelCategory.of(channel, taxonomy, categoryCode, confidence, labelSource, labeledAt)`
  - `InfluencerScore.of(campaign, channel, ruleTotal, ruleBreakdownJson, badges, grade, estimatedPrice, estimatedCpv, rubricVersion, scoredAt)`
  - `score.applyAiResult(aiTotal, aiBreakdownJson, inputHash, grade, total)` · `score.markHardFail(reason)` · `score.overrideQuotedPrice(price)`
  - Repository: `findByCampaignIdAndChannelId`, `findByCampaignIdOrderByTotalDesc`
- jsonb 컬럼은 `@JdbcTypeCode(SqlTypes.JSON) private String ruleBreakdown;` — 직렬화는 서비스 계층 책임(Plan 3)

- [ ] **Commit**

```bash
git add palim-automation/
git commit -m "인플루언서 등급표, 라이징 레이더 설계 및 구현 : feat : 캠페인·분류·점수 엔티티 추가 https://github.com/Cassiiopeia/palim/issues/41"
```

---

### Task 4: 스키마·엔티티 정합 통합 테스트

**Files:**
- Modify: `palim-app/build.gradle.kts` (testImplementation 에 `project(":palim-automation")` 추가)
- Create: `palim-app/src/test/java/kr/suhsaechan/palim/integration/InfluencerSchemaIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1~3 의 스키마·엔티티, 기존 `IntegrationTest` 기반 클래스(Testcontainers PostgreSQL)

- [ ] **Step 1: build.gradle.kts 수정**

`palim-app/build.gradle.kts` 의 testImplementation 목록(`testImplementation(project(":palim-audit"))` 등이 있는 블록)에 추가:

```kotlin
    testImplementation(project(":palim-automation"))
```

- [ ] **Step 2: 통합 테스트 작성**

`ddl-auto=validate` 가 전체 컨텍스트에서 엔티티↔스키마를 검증하므로, 컨텍스트가 뜨는 것 자체가 1차 검증이다. 그 위에 저장·조회·유니크 제약을 확인한다.

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import kr.suhsaechan.palim.automation.influencer.domain.Campaign;
import kr.suhsaechan.palim.automation.influencer.domain.CampaignRepository;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelSnapshot;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelSnapshotRepository;
import kr.suhsaechan.palim.automation.influencer.domain.DiscoverySource;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannelRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideo;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideoRepository;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class InfluencerSchemaIntegrationTest extends IntegrationTest {

    @Autowired private InfluencerChannelRepository channels;
    @Autowired private InfluencerVideoRepository videos;
    @Autowired private ChannelSnapshotRepository snapshots;
    @Autowired private CampaignRepository campaigns;

    private InfluencerChannel saveChannel(String youtubeId) {
        InfluencerChannel channel = InfluencerChannel.register(
                youtubeId, "채널A", "@channel-a", "KR", "UU-" + youtubeId,
                DiscoverySource.KEYWORD_SEARCH);
        return channels.save(channel);
    }

    @Test
    @DisplayName("채널을 저장하고 유튜브 ID 로 조회한다")
    void 채널_저장_조회() {
        saveChannel("ch-1");

        assertThat(channels.findByYoutubeChannelId("ch-1")).isPresent();
    }

    @Test
    @DisplayName("같은 유튜브 채널 ID 는 두 번 저장되지 않는다 — 발굴 경로가 겹쳐도 한 행이다")
    void 채널_중복_저장_불가() {
        saveChannel("ch-2");

        assertThatThrownBy(() -> channels.saveAndFlush(InfluencerChannel.register(
                "ch-2", "채널B", null, "KR", null, DiscoverySource.POPULAR_CHART)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("60초 이하 영상은 쇼츠로 자동 판정된다")
    void 쇼츠_자동_판정() {
        InfluencerChannel channel = saveChannel("ch-3");
        Instant now = Instant.parse("2026-08-11T00:00:00Z");

        InfluencerVideo shortForm = videos.save(InfluencerVideo.of(
                channel, "v-short", "쇼츠", now, 45, 1000, 10, 1, false, now));
        InfluencerVideo longForm = videos.save(InfluencerVideo.of(
                channel, "v-long", "롱폼", now, 300, 1000, 10, 1, false, now));

        assertThat(shortForm.isShortForm()).isTrue();
        assertThat(longForm.isShortForm()).isFalse();
    }

    @Test
    @DisplayName("채널당 하루 스냅샷은 한 행이다 — 배치가 여러 번 돌아도 곡선이 왜곡되지 않는다")
    void 스냅샷_하루_한행() {
        InfluencerChannel channel = saveChannel("ch-4");
        LocalDate day = LocalDate.of(2026, 8, 11);
        snapshots.save(ChannelSnapshot.of(channel, day, 50_000, 1_000_000, 120));

        assertThatThrownBy(() -> snapshots.saveAndFlush(
                ChannelSnapshot.of(channel, day, 50_100, 1_010_000, 121)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("캠페인은 스코어링 입력(CampaignTarget)으로 변환된다")
    void 캠페인_타깃_변환() {
        Campaign campaign = campaigns.save(Campaign.of(
                "테스트 캠페인", "생활용품", "30대 여성", "소구점", "금지 조건",
                50_000, 300_000, 10_000, 1_000_000));

        assertThat(campaign.toTarget().targetReachMin()).isEqualTo(50_000);
        assertThat(campaign.toTarget().subscriberMax()).isEqualTo(1_000_000);
    }
}
```

- [ ] **Step 3: push 후 CI 검증**

```bash
git add palim-app/ palim-automation/
git commit -m "인플루언서 등급표, 라이징 레이더 설계 및 구현 : test : 스키마·엔티티 정합 통합 테스트 추가 https://github.com/Cassiiopeia/palim/issues/41"
git push
```

CI `build` 잡의 `ddl-auto=validate` 통과 = 엔티티와 스키마가 일치한다는 확인이다.
