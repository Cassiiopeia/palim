# 인플루언서 스코어링 코어 구현 계획 (Plan 1/5)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `palim-automation` 모듈을 신설하고, 인플루언서 룰 점수 70점·라이징 지수 100점·등급·CPV 추정을 **DB 없는 순수 함수**로 구현한다.

**Architecture:** 스펙 `docs/superpowers/specs/2026-08-11-influencer-grading-design.md` §5·§7 의 스코어링 엔진. 입력은 `VideoSample` 레코드 목록 + 구독자 수, 출력은 점수 분해 레코드. 모든 임계값은 `influencer-scoring.yml` 로 외부화하고 구간 선형 보간(`PiecewiseLinear`)으로 계산한다.

**Tech Stack:** Java 25 / Spring Boot 4.x, Lombok, JUnit 5 + AssertJ. Spring 컨텍스트 불필요(YAML 바인딩 테스트만 `Binder` 사용).

**계획 시리즈** (각각 별도 plan 문서, 이 문서는 1번):
1. **스코어링 코어** (이 문서) — 모듈 골격 + 순수 계산 엔진
2. 데이터 모델 + YouTube API 클라이언트 + 수집
3. 발굴 파이프라인 + 야간 배치 + quota ledger
4. AI 심층 심사 + 자막 어댑터(scripts/)
5. 웹 화면 5종 + 라이징 레이더 화면 + 트렌드 + 텔레그램 알림

## Global Constraints

- 이슈: https://github.com/Cassiiopeia/palim/issues/41 — 브랜치 `20260811_#41_인플루언서_등급표_라이징_레이더_설계_및_구현` 에서 작업
- 커밋 컨벤션: `인플루언서 등급표, 라이징 레이더 설계 및 구현 : {타입} : {설명} https://github.com/Cassiiopeia/palim/issues/41`
- 커밋에 AI 흔적(Co-Authored-By, Generated with, 🤖, Claude-Session 등) **절대 금지** — CI guard 가 검사한다
- 시각은 전 계층 `Instant` (`LocalDateTime` 금지)
- 새 예외 클래스 금지 — `BusinessException` + `ErrorCode` 만 (이 계획에서는 ErrorCode 추가만 하고 사용은 Plan 2 부터)
- 저장소는 PUBLIC — 테스트 fixture 에 실존 채널명·발주사 정보 금지, 전부 합성(`ch-1`, `video-1`)
- 로컬 `./gradlew` 가 배포판 다운로드 차단으로 실패할 수 있다 — 그 경우 해당 Step 은 "컴파일 확인 불가" 로 표시하고 push 후 GitHub Actions 로 검증한다 (의도된 구조)
- record 컴포넌트와 같은 이름의 정적 팩토리 금지 (`of()`/`from()` 사용)

---

### Task 1: palim-automation 모듈 골격

**Files:**
- Modify: `settings.gradle.kts` (도메인 include 블록)
- Create: `palim-automation/build.gradle.kts`
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/package-info.java`

**Interfaces:**
- Consumes: `palim-common` (api 의존)
- Produces: 이후 모든 Task 가 이 모듈에 코드를 추가한다. 패키지 루트 `kr.suhsaechan.palim.automation`

- [ ] **Step 1: settings.gradle.kts 에 모듈 추가**

`// 도메인 — 서로를 의존하지 않는다` 블록의 `include("palim-incident")` 아래에 추가:

```kotlin
include("palim-automation")
```

- [ ] **Step 2: build.gradle.kts 작성**

`palim-automation/build.gradle.kts` — `palim-audit` 와 동일 패턴:

```kotlin
plugins {
    `java-library`
}

dependencies {
    // palim-common 이 spring-boot-starter-data-jpa 를 api 로 노출한다.
    api(project(":palim-common"))

    testImplementation(testFixtures(project(":palim-common")))
}
```

- [ ] **Step 3: package-info.java 작성**

`palim-automation/src/main/java/kr/suhsaechan/palim/automation/package-info.java`:

```java
/**
 * AI 업무자동화 모듈 도메인.
 *
 * <p>재고 도메인 동결(07-DECISIONS 023) 이후 새 기능이 들어가는 유일한 도메인 모듈이다.
 * 1호 하위 도메인은 인플루언서 등급표({@code influencer} 패키지) — 유튜브 공식 API 지표 기반
 * 룰 점수 70 + AI 심사 30, 라이징 지수 100 을 산출한다. 설계 원본은
 * {@code docs/superpowers/specs/2026-08-11-influencer-grading-design.md}.
 */
package kr.suhsaechan.palim.automation;
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :palim-automation:compileJava`
Expected: BUILD SUCCESSFUL (Gradle 배포판 차단 시 skip — push 후 CI 검증)

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts palim-automation/
git commit -m "인플루언서 등급표, 라이징 레이더 설계 및 구현 : feat : palim-automation 모듈 골격 신설 https://github.com/Cassiiopeia/palim/issues/41"
```

---

### Task 2: ErrorCode 인플루언서(Y) 계열 추가

**Files:**
- Modify: `palim-common/src/main/java/kr/suhsaechan/palim/common/error/ErrorCode.java` (마지막 항목 `INCIDENT_STATUS_INVALID` 뒤)
- Modify: `palim-common/src/main/resources/errors.properties`
- Modify: `palim-common/src/main/resources/errors_en.properties`

**Interfaces:**
- Produces: `ErrorCode.YOUTUBE_QUOTA_EXCEEDED` · `YOUTUBE_API_FAILED` · `TRANSCRIPT_UNAVAILABLE` · `INFLUENCER_CHANNEL_NOT_FOUND` · `INFLUENCER_CAMPAIGN_NOT_FOUND` — Plan 2~4 가 사용

- [ ] **Step 1: 기존 메시지 검증 테스트 확인**

Run: `grep -rl "messageKey\|errors.properties" palim-common/src/test` 로 ErrorCode 전수 검증 테스트 위치 확인 (있으면 메시지 누락 시 자동 실패하므로 별도 테스트 작성 불요)

- [ ] **Step 2: ErrorCode 추가**

`INCIDENT_STATUS_INVALID` 항목의 세미콜론을 콤마로 바꾸고 아래 추가. javadoc 접두사 표에도 `Y` 행(`인플루언서 · 유튜브`)을 추가한다:

```java
    // ==================================================================
    // 인플루언서 · 유튜브 (Y)
    // ==================================================================

    /** 일일 quota 소진. 오류가 아니라 정상 흐름 제어 — 커서를 저장하고 다음 실행에 재개한다. */
    YOUTUBE_QUOTA_EXCEEDED("Y001", HttpStatus.TOO_MANY_REQUESTS, LogLevel.INFO),

    /** YouTube API 호출 실패. 커서를 전진시키지 않고 다음 주기에 재시도한다. */
    YOUTUBE_API_FAILED("Y002", HttpStatus.BAD_GATEWAY, LogLevel.ERROR),

    /** 자막 수집 실패·차단. 메타+댓글 폴백으로 심사를 계속하므로 WARN 이다. */
    TRANSCRIPT_UNAVAILABLE("Y003", HttpStatus.BAD_GATEWAY, LogLevel.WARN),

    INFLUENCER_CHANNEL_NOT_FOUND("Y004", HttpStatus.NOT_FOUND, LogLevel.WARN),

    INFLUENCER_CAMPAIGN_NOT_FOUND("Y005", HttpStatus.NOT_FOUND, LogLevel.WARN);
```

- [ ] **Step 3: 메시지 추가**

`errors.properties`:

```properties
error.YOUTUBE_QUOTA_EXCEEDED=YouTube API 일일 할당량이 소진되었습니다. 다음 실행에 이어서 진행됩니다.
error.YOUTUBE_API_FAILED=YouTube API 호출에 실패했습니다.
error.TRANSCRIPT_UNAVAILABLE=자막을 가져올 수 없어 메타데이터와 댓글만으로 분석합니다.
error.INFLUENCER_CHANNEL_NOT_FOUND=채널을 찾을 수 없습니다.
error.INFLUENCER_CAMPAIGN_NOT_FOUND=캠페인을 찾을 수 없습니다.
```

`errors_en.properties`:

```properties
error.YOUTUBE_QUOTA_EXCEEDED=YouTube API daily quota exhausted. Processing resumes on the next run.
error.YOUTUBE_API_FAILED=YouTube API call failed.
error.TRANSCRIPT_UNAVAILABLE=Transcript unavailable; analysis continues with metadata and comments only.
error.INFLUENCER_CHANNEL_NOT_FOUND=Channel not found.
error.INFLUENCER_CAMPAIGN_NOT_FOUND=Campaign not found.
```

- [ ] **Step 4: 검증 테스트 실행**

Run: `./gradlew :palim-common:test`
Expected: PASS (전수 검증 테스트가 새 코드의 메시지 존재를 확인)

- [ ] **Step 5: Commit**

```bash
git add palim-common/
git commit -m "인플루언서 등급표, 라이징 레이더 설계 및 구현 : feat : ErrorCode Y 계열 5종 추가 https://github.com/Cassiiopeia/palim/issues/41"
```

---

### Task 3: PiecewiseLinear 보간 유틸

**Files:**
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/PiecewiseLinear.java`
- Test: `palim-automation/src/test/java/kr/suhsaechan/palim/automation/influencer/scoring/PiecewiseLinearTest.java`

**Interfaces:**
- Produces: `static double PiecewiseLinear.interpolate(List<List<Double>> curve, double x)` — curve 는 x 오름차순 `[[x,y],...]`, 범위 밖은 양 끝값으로 클램프. Task 5~7 의 모든 점수 곡선이 이것으로 계산된다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PiecewiseLinearTest {

    private static final List<List<Double>> CURVE =
            List.of(List.of(0.0, 0.0), List.of(0.08, 2.0), List.of(0.5, 14.0));

    @Test
    void 제어점_위의_값은_그대로_반환한다() {
        assertThat(PiecewiseLinear.interpolate(CURVE, 0.08)).isEqualTo(2.0);
    }

    @Test
    void 제어점_사이는_선형_보간한다() {
        // 0.08~0.5 구간의 중점 0.29 → 2.0~14.0 의 중점 8.0
        assertThat(PiecewiseLinear.interpolate(CURVE, 0.29)).isEqualTo(8.0);
    }

    @Test
    void 범위_밖은_양_끝값으로_클램프한다() {
        assertThat(PiecewiseLinear.interpolate(CURVE, -1.0)).isEqualTo(0.0);
        assertThat(PiecewiseLinear.interpolate(CURVE, 9.9)).isEqualTo(14.0);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :palim-automation:test --tests "*.PiecewiseLinearTest"`
Expected: FAIL (컴파일 오류 — PiecewiseLinear 미존재)

- [ ] **Step 3: 구현**

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.List;

/**
 * 구간 선형 보간.
 *
 * <p>스코어링 임계값을 if 분기로 하드코딩하면 캘리브레이션 때마다 코드를 고쳐야 한다.
 * 곡선을 YAML 제어점 {@code [[x,y],...]} 으로 두고 이 유틸로 계산하면 조정이 설정 변경으로 끝난다.
 */
public final class PiecewiseLinear {

    private PiecewiseLinear() {
    }

    /** curve 는 x 오름차순이어야 한다. 범위 밖 x 는 양 끝 y 로 클램프한다. */
    public static double interpolate(List<List<Double>> curve, double x) {
        if (x <= curve.getFirst().getFirst()) {
            return curve.getFirst().get(1);
        }
        if (x >= curve.getLast().getFirst()) {
            return curve.getLast().get(1);
        }
        for (int i = 1; i < curve.size(); i++) {
            double x1 = curve.get(i).getFirst();
            if (x <= x1) {
                double x0 = curve.get(i - 1).getFirst();
                double y0 = curve.get(i - 1).get(1);
                double y1 = curve.get(i).get(1);
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0);
            }
        }
        return curve.getLast().get(1);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :palim-automation:test --tests "*.PiecewiseLinearTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add palim-automation/
git commit -m "인플루언서 등급표, 라이징 레이더 설계 및 구현 : feat : 구간 선형 보간 유틸 추가 https://github.com/Cassiiopeia/palim/issues/41"
```

---

### Task 4: ScoringProperties + influencer-scoring.yml

**Files:**
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/ScoringProperties.java`
- Create: `palim-automation/src/main/resources/influencer-scoring.yml`
- Test: `palim-automation/src/test/java/kr/suhsaechan/palim/automation/influencer/scoring/ScoringPropertiesTest.java`

**Interfaces:**
- Produces: `ScoringProperties` record 트리 (아래 전체 정의). Task 5~7 이 생성자 주입 대신 파라미터로 받는다. Spring 빈 등록(`@ConfigurationProperties` 활성화)은 Plan 3 의 배치 조립에서 한다 — 여기서는 record 정의 + YAML 이 바인딩 가능함을 `Binder` 로 검증만 한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ScoringPropertiesTest {

    @Test
    void 기본_YAML_이_바인딩되고_배점_합이_스펙과_일치한다() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("influencer-scoring.yml"));
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new PropertiesPropertySource("scoring", yaml.getObject()));

        ScoringProperties props = new Binder(ConfigurationPropertySources.get(env))
                .bind("palim.influencer.scoring", ScoringProperties.class)
                .get();

        assertThat(props.shortsMaxSeconds()).isEqualTo(60);
        assertThat(props.windowSize()).isEqualTo(50);
        // 룰 만점 합 70: reach 14 + vsr 14 + momentum(6+5+3) + engagement 12 + activity(5+3) + stability 8
        assertThat(props.rule().reachPoints()
                + props.rule().vsr().curve().getLast().get(1)
                + props.rule().momentum().trendCurve().getLast().get(1)
                + props.rule().momentum().peakCurve().getLast().get(1)
                + props.rule().momentum().crashCurve().getLast().get(1)
                + props.rule().engagement().curve().getLast().get(1)
                + props.rule().activity().uploadsPoints()
                + props.rule().activity().recencyCurve().getFirst().get(1)
                + props.rule().stability().curve().getFirst().get(1))
                .isEqualTo(70.0);
        // 라이징 만점 합 100: 30+25+20+15+10
        assertThat(props.rising().vsrHeatCurve().getLast().get(1)
                + props.rising().accelCurve().getLast().get(1)
                + props.rising().velocityCurve().getLast().get(1)
                + props.rising().burstCurve().getLast().get(1)
                + props.rising().untappedPoints())
                .isEqualTo(100.0);
        assertThat(props.grade().s()).isEqualTo(85);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :palim-automation:test --tests "*.ScoringPropertiesTest"`
Expected: FAIL (컴파일 오류)

- [ ] **Step 3: ScoringProperties 정의**

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.List;
import java.util.Map;

/**
 * 스코어링 임계값·배점 설정. 원본은 {@code influencer-scoring.yml} 이며, 발주사와 합의한
 * 루브릭 문서와 1:1 대응한다 — 코드가 아니라 이 설정을 조정하는 것이 캘리브레이션이다.
 *
 * <p>curve 필드는 전부 {@link PiecewiseLinear#interpolate(List, double)} 의 제어점이다.
 */
public record ScoringProperties(
        int shortsMaxSeconds,
        int windowSize,
        HardFilterProps hardFilter,
        RuleProps rule,
        RisingProps rising,
        GradeProps grade,
        CpvProps cpv) {

    public record HardFilterProps(int maxDaysSinceUpload, int minLongformCount) {
    }

    public record RuleProps(
            double reachPoints,
            CurveProps vsr,
            MomentumProps momentum,
            EngagementProps engagement,
            ActivityProps activity,
            CurveOnlyProps stability) {
    }

    public record CurveProps(List<List<Double>> curve) {
    }

    public record CurveOnlyProps(List<List<Double>> curve) {
    }

    public record MomentumProps(
            List<List<Double>> trendCurve,
            List<List<Double>> peakCurve,
            double crashThreshold,
            List<List<Double>> crashCurve) {
    }

    public record EngagementProps(int commentWeight, List<List<Double>> curve) {
    }

    public record ActivityProps(double uploadsPoints, int uploadsTarget, List<List<Double>> recencyCurve) {
    }

    public record RisingProps(
            List<List<Double>> vsrHeatCurve,
            List<List<Double>> accelCurve,
            List<List<Double>> velocityCurve,
            List<List<Double>> burstCurve,
            double untappedPoints,
            double untappedMaxPaidRatio,
            long untappedMaxSubscribers,
            double badgeThreshold) {
    }

    public record GradeProps(int s, int a, int b, int c) {
    }

    /** 카테고리별 구독자당 추정 단가 계수(원). 미등록 카테고리는 defaultCoefficient. */
    public record CpvProps(double defaultCoefficient, Map<String, Double> categoryCoefficients) {
    }
}
```

- [ ] **Step 4: influencer-scoring.yml 작성**

```yaml
# 인플루언서 스코어링 루브릭 초기값.
# 스펙: docs/superpowers/specs/2026-08-11-influencer-grading-design.md §5, §7
# 이 값은 실측 감각 기반 초기값이다 — 캘리브레이션(정답셋 15~20개 대조) 후 조정한다.
palim:
  influencer:
    scoring:
      shorts-max-seconds: 60      # 이하 = 쇼츠로 분리, 점수 계산 제외
      window-size: 50             # 관측 창: 최근 롱폼 N개
      hard-filter:
        max-days-since-upload: 90
        min-longform-count: 5
      rule:                       # 만점 합 70
        reach-points: 14.0        # 캠페인 목표 도달 구간 대비 로그 감쇠
        vsr:                      # 도달 효율 = 조회수 중앙값 / 구독자
          curve: [[0.0, 0.0], [0.08, 2.0], [0.15, 5.0], [0.3, 9.0], [0.5, 14.0]]
        momentum:                 # 6 + 5 + 3 = 14
          trend-curve: [[0.0, 0.0], [0.7, 1.5], [0.9, 3.0], [1.2, 4.5], [1.5, 6.0]]
          peak-curve: [[0.0, 0.0], [0.3, 1.0], [0.5, 2.5], [0.8, 4.0], [1.0, 5.0]]
          crash-threshold: 0.5    # 미만 = 0점 + 급락 배지
          crash-curve: [[0.5, 0.0], [0.8, 3.0]]
        engagement:               # (좋아요 + 댓글×가중) / 조회수, 중앙값
          comment-weight: 3
          curve: [[0.0, 0.0], [0.015, 2.5], [0.03, 5.0], [0.05, 8.5], [0.08, 12.0]]
        activity:                 # 5 + 3 = 8
          uploads-points: 5.0
          uploads-target: 12      # 90일 12회(주1회) = 만점
          recency-curve: [[0.0, 3.0], [7.0, 3.0], [14.0, 2.25], [30.0, 1.5], [60.0, 0.75], [90.0, 0.0]]
        stability:                # CV = (Q3-Q1)/중앙값, 낮을수록 좋음
          curve: [[0.0, 8.0], [0.4, 8.0], [0.8, 5.0], [1.5, 2.0], [3.0, 0.0]]
      rising:                     # 만점 합 100
        vsr-heat-curve: [[1.0, 0.0], [1.5, 30.0]]
        accel-curve: [[1.0, 0.0], [2.0, 25.0]]
        velocity-curve: [[1.0, 0.0], [3.0, 20.0]]
        burst-curve: [[1.0, 0.0], [2.0, 15.0]]
        untapped-points: 10.0
        untapped-max-paid-ratio: 0.05
        untapped-max-subscribers: 100000
        badge-threshold: 70.0     # 이상 = 라이징 배지 + 매일 스냅샷 대상
      grade:
        s: 85
        a: 70
        b: 55
        c: 40
      cpv:
        default-coefficient: 25.0   # 구독자 1명당 추정 단가(원) — 업계 관행 추정 초기값
        category-coefficients: {}   # 예: { beauty: 40.0 } — 자체 카테고리 코드 기준, 발주사 합의 후 채움
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :palim-automation:test --tests "*.ScoringPropertiesTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add palim-automation/
git commit -m "인플루언서 등급표, 라이징 레이더 설계 및 구현 : feat : 스코어링 설정 YAML 외부화 https://github.com/Cassiiopeia/palim/issues/41"
```

---

### Task 5: VideoSample + MetricsCalculator

**Files:**
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/VideoSample.java`
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/ChannelMetrics.java`
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/MetricsCalculator.java`
- Test: `palim-automation/src/test/java/kr/suhsaechan/palim/automation/influencer/scoring/MetricsCalculatorTest.java`

**Interfaces:**
- Consumes: `ScoringProperties` (shortsMaxSeconds, windowSize, engagement.commentWeight)
- Produces:
  - `record VideoSample(String videoId, Instant publishedAt, int durationSeconds, long viewCount, long likeCount, long commentCount, boolean paidPromotion)`
  - `record ChannelMetrics(int longformCount, double medianViews, double vsr, double engagementRate, double cv, double trendRatio, double peakRatio, double crashRatio, int uploads90d, long daysSinceLastUpload, double paidRatio, double velocityRatio, double burstRatio)`
  - `ChannelMetrics MetricsCalculator.calculate(List<VideoSample> videos, long subscriberCount, Instant now, ScoringProperties props)` — videos 정렬 불문(내부에서 publishedAt 내림차순 정렬), 쇼츠 제외, 표본 부족 시 비율 필드는 중립값 `1.0`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricsCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    /** i일 전 업로드, 조회수 views 인 롱폼(300초) 영상. */
    private static VideoSample longform(int daysAgo, long views, long likes, long comments) {
        return new VideoSample("v-" + daysAgo, NOW.minus(Duration.ofDays(daysAgo)),
                300, views, likes, comments, false);
    }

    private static ScoringProperties props() {
        return ScoringFixtures.defaultProps(); // Task 5 Step 3 에서 함께 만드는 테스트 픽스처
    }

    @Test
    void 쇼츠는_지표에서_제외된다() {
        List<VideoSample> videos = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            videos.add(longform(i * 3, 10_000, 500, 100));
        }
        // 조회수 100만짜리 쇼츠(45초) — 포함되면 중앙값이 왜곡된다
        videos.add(new VideoSample("short-1", NOW.minus(Duration.ofDays(1)), 45, 1_000_000, 0, 0, false));

        ChannelMetrics m = MetricsCalculator.calculate(videos, 50_000, NOW, props());

        assertThat(m.longformCount()).isEqualTo(10);
        assertThat(m.medianViews()).isEqualTo(10_000.0);
    }

    @Test
    void 중앙값과_VSR_과_참여율을_계산한다() {
        // 조회수 8k/9k/10k/11k/12k → 중앙값 10k, 구독 50k → VSR 0.2
        List<VideoSample> videos = List.of(
                longform(5, 8_000, 400, 80), longform(10, 9_000, 450, 90),
                longform(15, 10_000, 500, 100), longform(20, 11_000, 550, 110),
                longform(25, 12_000, 600, 120));

        ChannelMetrics m = MetricsCalculator.calculate(videos, 50_000, NOW, props());

        assertThat(m.medianViews()).isEqualTo(10_000.0);
        assertThat(m.vsr()).isCloseTo(0.2, within(1e-9));
        // 영상별 ER = (500 + 100*3)/10000 = 0.08 전부 동일 → 중앙값 0.08
        assertThat(m.engagementRate()).isCloseTo(0.08, within(1e-9));
    }

    @Test
    void 최근10_대_직전10_추세와_급락_비율을_계산한다() {
        List<VideoSample> videos = new ArrayList<>();
        // 최근 10개(1~30일 전): 20k, 직전 10개(31~60일 전): 10k → trendRatio 2.0
        for (int i = 0; i < 10; i++) {
            videos.add(longform(1 + i * 3, 20_000, 1000, 200));
        }
        for (int i = 0; i < 10; i++) {
            videos.add(longform(31 + i * 3, 10_000, 500, 100));
        }

        ChannelMetrics m = MetricsCalculator.calculate(videos, 50_000, NOW, props());

        assertThat(m.trendRatio()).isCloseTo(2.0, within(1e-9));
        // crashRatio = 최근5 중앙값 20k / 6~20번째 중앙값 — 6~10번째 20k, 11~20번째 10k → 중앙값 10k... 표본 구성상 최근5=20k, v[5..20) 중앙값 10k → 2.0 (급락 아님)
        assertThat(m.crashRatio()).isGreaterThan(1.0);
    }

    @Test
    void 표본이_20개_미만이면_추세_비율은_중립값이다() {
        List<VideoSample> videos = List.of(
                longform(5, 10_000, 500, 100), longform(15, 10_000, 500, 100),
                longform(25, 10_000, 500, 100), longform(35, 10_000, 500, 100),
                longform(45, 10_000, 500, 100));

        ChannelMetrics m = MetricsCalculator.calculate(videos, 50_000, NOW, props());

        assertThat(m.trendRatio()).isEqualTo(1.0);
        assertThat(m.peakRatio()).isEqualTo(1.0);
        assertThat(m.crashRatio()).isEqualTo(1.0);
    }

    @Test
    void 활동성_지표를_계산한다() {
        List<VideoSample> videos = List.of(
                longform(3, 10_000, 500, 100), longform(40, 10_000, 500, 100),
                longform(80, 10_000, 500, 100), longform(100, 10_000, 500, 100),
                longform(120, 10_000, 500, 100));

        ChannelMetrics m = MetricsCalculator.calculate(videos, 50_000, NOW, props());

        assertThat(m.uploads90d()).isEqualTo(3);
        assertThat(m.daysSinceLastUpload()).isEqualTo(3);
    }

    @Test
    void 유료광고_비율을_계산한다() {
        List<VideoSample> videos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            videos.add(longform(1 + i * 5, 10_000, 500, 100));
        }
        videos.add(new VideoSample("paid-1", NOW.minus(Duration.ofDays(50)), 300, 10_000, 500, 100, true));
        videos.add(new VideoSample("paid-2", NOW.minus(Duration.ofDays(55)), 300, 10_000, 500, 100, true));

        ChannelMetrics m = MetricsCalculator.calculate(videos, 50_000, NOW, props());

        assertThat(m.paidRatio()).isCloseTo(0.2, within(1e-9));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :palim-automation:test --tests "*.MetricsCalculatorTest"`
Expected: FAIL (컴파일 오류)

- [ ] **Step 3: 테스트 픽스처 ScoringFixtures 작성**

`palim-automation/src/test/java/kr/suhsaechan/palim/automation/influencer/scoring/ScoringFixtures.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** 스코어링 테스트 공용 픽스처 — 실제 배포 YAML 초기값을 그대로 바인딩해 쓴다. */
final class ScoringFixtures {

    private ScoringFixtures() {
    }

    static ScoringProperties defaultProps() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("influencer-scoring.yml"));
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new PropertiesPropertySource("scoring", yaml.getObject()));
        return new Binder(ConfigurationPropertySources.get(env))
                .bind("palim.influencer.scoring", ScoringProperties.class)
                .get();
    }
}
```

- [ ] **Step 4: VideoSample·ChannelMetrics·MetricsCalculator 구현**

`VideoSample.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import java.time.Instant;

/** 스코어링 입력이 되는 영상 1건의 스냅샷. 수집 계층(Plan 2)이 이 형태로 변환해 넘긴다. */
public record VideoSample(
        String videoId,
        Instant publishedAt,
        int durationSeconds,
        long viewCount,
        long likeCount,
        long commentCount,
        boolean paidPromotion) {
}
```

`ChannelMetrics.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

/**
 * 채널 1개의 정량 지표. 전부 롱폼 기준·중앙값 집계다(스펙 §5 공통 규칙).
 *
 * <p>비율 필드(trend/peak/crash/velocity/burst)는 표본 부족 시 중립값 {@code 1.0} 이다 —
 * 0 이면 신생 채널이 부당하게 감점되고, 만점이면 부당하게 가점되기 때문이다.
 */
public record ChannelMetrics(
        int longformCount,
        double medianViews,
        double vsr,
        double engagementRate,
        double cv,
        double trendRatio,
        double peakRatio,
        double crashRatio,
        int uploads90d,
        long daysSinceLastUpload,
        double paidRatio,
        double velocityRatio,
        double burstRatio) {
}
```

`MetricsCalculator.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** {@link VideoSample} 목록 → {@link ChannelMetrics}. 순수 함수 — DB·API 의존 없음. */
public final class MetricsCalculator {

    private MetricsCalculator() {
    }

    public static ChannelMetrics calculate(
            List<VideoSample> videos, long subscriberCount, Instant now, ScoringProperties props) {

        List<VideoSample> longform = videos.stream()
                .filter(v -> v.durationSeconds() > props.shortsMaxSeconds())
                .sorted(Comparator.comparing(VideoSample::publishedAt).reversed())
                .limit(props.windowSize())
                .toList();

        if (longform.isEmpty()) {
            return new ChannelMetrics(0, 0, 0, 0, 0, 1.0, 1.0, 1.0, 0, Long.MAX_VALUE, 0, 1.0, 1.0);
        }

        double[] views = longform.stream().mapToDouble(VideoSample::viewCount).sorted().toArray();
        double medianViews = median(views);
        double vsr = subscriberCount > 0 ? medianViews / subscriberCount : 0;

        int weight = props.rule().engagement().commentWeight();
        double engagementRate = median(longform.stream()
                .filter(v -> v.viewCount() > 0)
                .mapToDouble(v -> (v.likeCount() + (double) weight * v.commentCount()) / v.viewCount())
                .sorted().toArray());

        double q1 = quantile(views, 0.25);
        double q3 = quantile(views, 0.75);
        double cv = medianViews > 0 ? (q3 - q1) / medianViews : 0;

        boolean enough = longform.size() >= 20;
        double trendRatio = enough ? ratio(viewsMedian(longform, 0, 10), viewsMedian(longform, 10, 20)) : 1.0;
        double crashRatio = enough ? ratio(viewsMedian(longform, 0, 5), viewsMedian(longform, 5, 20)) : 1.0;
        double peakRatio = enough ? peakRatio(longform) : 1.0;

        int uploads90d = (int) longform.stream()
                .filter(v -> Duration.between(v.publishedAt(), now).toDays() <= 90)
                .count();
        long daysSinceLastUpload = Duration.between(longform.getFirst().publishedAt(), now).toDays();

        double paidRatio = longform.stream().filter(VideoSample::paidPromotion).count()
                / (double) longform.size();

        double velocityRatio = enough
                ? ratio(vpdMedian(longform, 0, 5, now), vpdMedian(longform, 5, 20, now))
                : 1.0;
        double burstRatio = enough
                ? ratio(commentRateMedian(longform, 0, 5), commentRateMedian(longform, 0, longform.size()))
                : 1.0;

        return new ChannelMetrics(longform.size(), medianViews, vsr, engagementRate, cv,
                trendRatio, peakRatio, crashRatio, uploads90d, daysSinceLastUpload, paidRatio,
                velocityRatio, burstRatio);
    }

    /** 최근 50개 창 안에서 "연속 5개 중앙값"의 최대(=피크) 대비 최근 5개 중앙값. 단발 떡상 오탐 방지. */
    private static double peakRatio(List<VideoSample> longform) {
        double recent = viewsMedian(longform, 0, 5);
        double peak = 0;
        for (int i = 0; i + 5 <= longform.size(); i++) {
            peak = Math.max(peak, viewsMedian(longform, i, i + 5));
        }
        return ratio(recent, peak);
    }

    private static double viewsMedian(List<VideoSample> videos, int from, int to) {
        return median(videos.subList(from, Math.min(to, videos.size())).stream()
                .mapToDouble(VideoSample::viewCount).sorted().toArray());
    }

    private static double vpdMedian(List<VideoSample> videos, int from, int to, Instant now) {
        return median(videos.subList(from, Math.min(to, videos.size())).stream()
                .mapToDouble(v -> v.viewCount()
                        / (double) Math.max(1, Duration.between(v.publishedAt(), now).toDays()))
                .sorted().toArray());
    }

    private static double commentRateMedian(List<VideoSample> videos, int from, int to) {
        return median(videos.subList(from, Math.min(to, videos.size())).stream()
                .filter(v -> v.viewCount() > 0)
                .mapToDouble(v -> v.commentCount() / (double) v.viewCount())
                .sorted().toArray());
    }

    private static double ratio(double numerator, double denominator) {
        return denominator > 0 ? numerator / denominator : 1.0;
    }

    /** sorted 배열의 중앙값. */
    private static double median(double[] sorted) {
        return quantile(sorted, 0.5);
    }

    /** sorted 배열의 분위수 — 선형 보간 방식. */
    private static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :palim-automation:test --tests "*.MetricsCalculatorTest"`
Expected: PASS (6 tests)

- [ ] **Step 6: Commit**

```bash
git add palim-automation/
git commit -m "인플루언서 등급표, 라이징 레이더 설계 및 구현 : feat : 채널 정량 지표 계산기 구현 https://github.com/Cassiiopeia/palim/issues/41"
```

---

### Task 6: HardFilter + RuleScorer (룰 70점 + 배지)

**Files:**
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/CampaignTarget.java`
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/HardFailReason.java`
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/HardFilter.java`
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/Badge.java`
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/RuleScore.java`
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/RuleScorer.java`
- Test: `palim-automation/src/test/java/kr/suhsaechan/palim/automation/influencer/scoring/HardFilterTest.java`
- Test: `palim-automation/src/test/java/kr/suhsaechan/palim/automation/influencer/scoring/RuleScorerTest.java`

**Interfaces:**
- Consumes: `ChannelMetrics`, `ScoringProperties`
- Produces:
  - `record CampaignTarget(long targetReachMin, long targetReachMax, long subscriberMin, long subscriberMax)`
  - `enum HardFailReason { INACTIVE, INSUFFICIENT_VIDEOS, BELOW_SUBSCRIBER_MIN, MANUALLY_EXCLUDED }`
  - `Optional<HardFailReason> HardFilter.check(ChannelMetrics m, long subscriberCount, CampaignTarget target, boolean manuallyExcluded, ScoringProperties props)`
  - `enum Badge { CRASH, RISING, TREND }` (TREND 부여는 Plan 5, 정의만 여기서)
  - `record RuleScore(double total, Map<String, Double> breakdown, Set<Badge> badges)` — breakdown 키: `"reach"`, `"vsr"`, `"momentum"`, `"engagement"`, `"activity"`, `"stability"`
  - `RuleScore RuleScorer.score(ChannelMetrics m, long subscriberCount, CampaignTarget target, ScoringProperties props)`

- [ ] **Step 1: HardFilter 실패하는 테스트 작성**

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HardFilterTest {

    private static final CampaignTarget TARGET = new CampaignTarget(50_000, 300_000, 10_000, 1_000_000);

    private static ChannelMetrics metrics(int longformCount, long daysSinceLastUpload) {
        return new ChannelMetrics(longformCount, 100_000, 0.5, 0.05, 0.3,
                1.0, 1.0, 1.0, 8, daysSinceLastUpload, 0.1, 1.0, 1.0);
    }

    @Test
    void 마지막_업로드_90일_초과는_INACTIVE() {
        assertThat(HardFilter.check(metrics(20, 91), 50_000, TARGET, false, ScoringFixtures.defaultProps()))
                .contains(HardFailReason.INACTIVE);
    }

    @Test
    void 롱폼_5개_미만은_INSUFFICIENT_VIDEOS() {
        assertThat(HardFilter.check(metrics(4, 3), 50_000, TARGET, false, ScoringFixtures.defaultProps()))
                .contains(HardFailReason.INSUFFICIENT_VIDEOS);
    }

    @Test
    void 캠페인_구독자_하한_미달은_BELOW_SUBSCRIBER_MIN() {
        assertThat(HardFilter.check(metrics(20, 3), 9_999, TARGET, false, ScoringFixtures.defaultProps()))
                .contains(HardFailReason.BELOW_SUBSCRIBER_MIN);
    }

    @Test
    void 수동_제외는_MANUALLY_EXCLUDED() {
        assertThat(HardFilter.check(metrics(20, 3), 50_000, TARGET, true, ScoringFixtures.defaultProps()))
                .contains(HardFailReason.MANUALLY_EXCLUDED);
    }

    @Test
    void 전부_통과하면_empty() {
        assertThat(HardFilter.check(metrics(20, 3), 50_000, TARGET, false, ScoringFixtures.defaultProps()))
                .isEmpty();
    }
}
```

- [ ] **Step 2: RuleScorer 실패하는 테스트 작성**

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class RuleScorerTest {

    private static final CampaignTarget TARGET = new CampaignTarget(50_000, 300_000, 10_000, 1_000_000);

    @Test
    void 이상적인_채널은_만점에_수렴한다() {
        // 목표 구간 내 도달 · VSR 0.5 · 상승 추세 · ER 8% · 주1회 업로드 · 저변동
        ChannelMetrics m = new ChannelMetrics(50, 100_000, 0.5, 0.08, 0.3,
                1.5, 1.0, 1.0, 12, 3, 0.1, 1.0, 1.0);

        RuleScore score = RuleScorer.score(m, 200_000, TARGET, ScoringFixtures.defaultProps());

        assertThat(score.total()).isCloseTo(70.0, within(0.01));
        assertThat(score.badges()).isEmpty();
    }

    @Test
    void 죽은_채널은_낮은_점수를_받는다() {
        // VSR 0.05 · 하락 추세 · ER 1% · 업로드 뜸함 · 고변동
        ChannelMetrics m = new ChannelMetrics(50, 5_000, 0.05, 0.01, 2.0,
                0.6, 0.2, 0.9, 2, 45, 0.1, 1.0, 1.0);

        RuleScore score = RuleScorer.score(m, 100_000, TARGET, ScoringFixtures.defaultProps());

        assertThat(score.total()).isLessThan(20.0);
    }

    @Test
    void 급락_채널은_crash_0점과_배지를_받는다() {
        ChannelMetrics m = new ChannelMetrics(50, 100_000, 0.5, 0.08, 0.3,
                1.5, 1.0, 0.4, 12, 3, 0.1, 1.0, 1.0); // crashRatio 0.4 < 0.5

        RuleScore score = RuleScorer.score(m, 200_000, TARGET, ScoringFixtures.defaultProps());

        assertThat(score.badges()).contains(Badge.CRASH);
        assertThat(score.total()).isCloseTo(67.0, within(0.01)); // 만점 70 - crash 3
    }

    @Test
    void 목표_도달_구간_미달은_로그_감쇠된다() {
        // 목표 하한 50k 의 1/10 → reach 0점, 1/2 → 약 70% 점
        ChannelMetrics tenth = new ChannelMetrics(50, 5_000, 0.5, 0.08, 0.3,
                1.5, 1.0, 1.0, 12, 3, 0.1, 1.0, 1.0);
        ChannelMetrics half = new ChannelMetrics(50, 25_000, 0.5, 0.08, 0.3,
                1.5, 1.0, 1.0, 12, 3, 0.1, 1.0, 1.0);

        var props = ScoringFixtures.defaultProps();
        assertThat(RuleScorer.score(tenth, 200_000, TARGET, props).breakdown().get("reach"))
                .isCloseTo(0.0, within(0.01));
        assertThat(RuleScorer.score(half, 200_000, TARGET, props).breakdown().get("reach"))
                .isCloseTo(14.0 * (1 + Math.log10(0.5)), within(0.01));
    }

    @Test
    void 목표_구간_초과도_감쇠된다_상한의_2배면_reach_약70퍼센트() {
        ChannelMetrics over = new ChannelMetrics(50, 600_000, 0.5, 0.08, 0.3,
                1.5, 1.0, 1.0, 12, 3, 0.1, 1.0, 1.0); // 상한 300k 의 2배

        double reach = RuleScorer.score(over, 2_000_000, TARGET, ScoringFixtures.defaultProps())
                .breakdown().get("reach");

        assertThat(reach).isCloseTo(14.0 * (1 + Math.log10(0.5)), within(0.01));
    }

    @Test
    void breakdown_합계는_total_과_일치한다() {
        ChannelMetrics m = new ChannelMetrics(50, 80_000, 0.3, 0.04, 0.6,
                1.1, 0.7, 0.9, 8, 10, 0.2, 1.0, 1.0);

        RuleScore score = RuleScorer.score(m, 250_000, TARGET, ScoringFixtures.defaultProps());

        double sum = score.breakdown().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(score.total()).isCloseTo(sum, within(1e-9));
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :palim-automation:test --tests "*.HardFilterTest" --tests "*.RuleScorerTest"`
Expected: FAIL (컴파일 오류)

- [ ] **Step 4: 구현**

`CampaignTarget.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

/** 캠페인 브리프 중 스코어링에 필요한 목표 구간. 점수는 항상 캠페인 기준이다(스펙 §1). */
public record CampaignTarget(
        long targetReachMin, long targetReachMax, long subscriberMin, long subscriberMax) {
}
```

`HardFailReason.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

/** 하드 탈락 사유. AI 는 탈락 권한이 없다 — 여기 있는 정량 조건과 수동 제외만 탈락시킨다. */
public enum HardFailReason {
    INACTIVE,
    INSUFFICIENT_VIDEOS,
    BELOW_SUBSCRIBER_MIN,
    MANUALLY_EXCLUDED
}
```

`HardFilter.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.Optional;

/** 점수 계산 전 선별. 탈락이면 점수를 계산하지 않는다(quota·AI 비용 절약). */
public final class HardFilter {

    private HardFilter() {
    }

    public static Optional<HardFailReason> check(
            ChannelMetrics m, long subscriberCount, CampaignTarget target,
            boolean manuallyExcluded, ScoringProperties props) {

        if (manuallyExcluded) {
            return Optional.of(HardFailReason.MANUALLY_EXCLUDED);
        }
        if (m.daysSinceLastUpload() > props.hardFilter().maxDaysSinceUpload()) {
            return Optional.of(HardFailReason.INACTIVE);
        }
        if (m.longformCount() < props.hardFilter().minLongformCount()) {
            return Optional.of(HardFailReason.INSUFFICIENT_VIDEOS);
        }
        if (subscriberCount < target.subscriberMin()) {
            return Optional.of(HardFailReason.BELOW_SUBSCRIBER_MIN);
        }
        return Optional.empty();
    }
}
```

`Badge.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

/** 등급표에 점수와 별도로 크게 노출하는 상태 배지. */
public enum Badge {
    /** 알고리즘 이탈 의심 — 최근 5편 중앙값이 직전 대비 절반 미만. */
    CRASH,
    /** 라이징 지수가 임계 이상 — 매일 스냅샷 대상. */
    RISING,
    /** 최근 영상이 주간 뜨는 키워드와 겹침(부여 로직은 트렌드 모듈). */
    TREND
}
```

`RuleScore.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.Map;
import java.util.Set;

/** 룰 70점 산출 결과. breakdown 은 화면의 점수 분해 표시와 캘리브레이션 대조에 쓴다. */
public record RuleScore(double total, Map<String, Double> breakdown, Set<Badge> badges) {
}
```

`RuleScorer.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 룰 점수 70점. 조회수 계열(reach + vsr + momentum = 42점)이 60% — 광고 단가는 구독자를
 * 따라가지만 성과는 조회수로 나오는 괴리를 겨냥한 의도된 배분이다(스펙 §5).
 */
public final class RuleScorer {

    private RuleScorer() {
    }

    public static RuleScore score(
            ChannelMetrics m, long subscriberCount, CampaignTarget target, ScoringProperties props) {

        Map<String, Double> breakdown = new LinkedHashMap<>();
        Set<Badge> badges = EnumSet.noneOf(Badge.class);
        var rule = props.rule();

        breakdown.put("reach", reachScore(m.medianViews(), target, rule.reachPoints()));
        breakdown.put("vsr", PiecewiseLinear.interpolate(rule.vsr().curve(), m.vsr()));

        double momentum = PiecewiseLinear.interpolate(rule.momentum().trendCurve(), m.trendRatio())
                + PiecewiseLinear.interpolate(rule.momentum().peakCurve(), m.peakRatio());
        if (m.crashRatio() < rule.momentum().crashThreshold()) {
            badges.add(Badge.CRASH);
        } else {
            momentum += PiecewiseLinear.interpolate(rule.momentum().crashCurve(), m.crashRatio());
        }
        breakdown.put("momentum", momentum);

        breakdown.put("engagement",
                PiecewiseLinear.interpolate(rule.engagement().curve(), m.engagementRate()));

        double uploads = rule.activity().uploadsPoints()
                * Math.min(1.0, m.uploads90d() / (double) rule.activity().uploadsTarget());
        double recency = PiecewiseLinear.interpolate(rule.activity().recencyCurve(),
                m.daysSinceLastUpload());
        breakdown.put("activity", uploads + recency);

        breakdown.put("stability", PiecewiseLinear.interpolate(rule.stability().curve(), m.cv()));

        double total = breakdown.values().stream().mapToDouble(Double::doubleValue).sum();
        return new RuleScore(total, breakdown, badges);
    }

    /**
     * 실도달량 — 캠페인 목표 도달 구간 내 만점, 구간 밖은 로그 스케일 감쇠.
     * r = 구간 대비 비율(하한 미달 v/min, 상한 초과 max/v), score = points * max(0, 1 + log10(r)).
     * r=1 → 만점, r=0.5 → 약 70%, r=0.1 → 0.
     */
    private static double reachScore(double medianViews, CampaignTarget target, double points) {
        double r;
        if (medianViews < target.targetReachMin()) {
            r = medianViews / target.targetReachMin();
        } else if (medianViews > target.targetReachMax()) {
            r = target.targetReachMax() / medianViews;
        } else {
            r = 1.0;
        }
        return r <= 0 ? 0 : points * Math.max(0, 1 + Math.log10(r));
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :palim-automation:test --tests "*.HardFilterTest" --tests "*.RuleScorerTest"`
Expected: PASS (11 tests)

- [ ] **Step 6: Commit**

```bash
git add palim-automation/
git commit -m "인플루언서 등급표, 라이징 레이더 설계 및 구현 : feat : 하드 필터와 룰 점수 70점 스코어러 구현 https://github.com/Cassiiopeia/palim/issues/41"
```

---

### Task 7: RisingIndexCalculator (라이징 지수 100점)

**Files:**
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/RisingIndex.java`
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/RisingIndexCalculator.java`
- Test: `palim-automation/src/test/java/kr/suhsaechan/palim/automation/influencer/scoring/RisingIndexCalculatorTest.java`

**Interfaces:**
- Consumes: `ChannelMetrics`, `ScoringProperties.RisingProps`, `Badge.RISING`
- Produces:
  - `record RisingIndex(double total, Map<String, Double> breakdown, boolean risingBadge)` — breakdown 키: `"vsrHeat"`, `"accel"`, `"velocity"`, `"burst"`, `"untapped"`
  - `RisingIndex RisingIndexCalculator.calculate(ChannelMetrics m, long subscriberCount, ScoringProperties props)`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class RisingIndexCalculatorTest {

    @Test
    void 폭발_직전_채널은_만점에_수렴하고_배지를_받는다() {
        // VSR 2.0(과열) · 가속 2배 · 조회속도 3배 · 참여 2배 · 광고 이력 없음 · 구독 5만
        ChannelMetrics m = new ChannelMetrics(30, 100_000, 2.0, 0.06, 0.5,
                2.0, 1.0, 1.5, 10, 2, 0.0, 3.0, 2.0);

        RisingIndex index = RisingIndexCalculator.calculate(m, 50_000, ScoringFixtures.defaultProps());

        assertThat(index.total()).isCloseTo(100.0, within(0.01));
        assertThat(index.risingBadge()).isTrue();
    }

    @Test
    void 정체_채널은_0점이고_배지가_없다() {
        // VSR 0.2 · 가속 없음 · 속도 보통 · 참여 보통
        ChannelMetrics m = new ChannelMetrics(30, 20_000, 0.2, 0.03, 0.5,
                1.0, 0.8, 1.0, 6, 5, 0.2, 1.0, 1.0);

        RisingIndex index = RisingIndexCalculator.calculate(m, 100_000, ScoringFixtures.defaultProps());

        assertThat(index.total()).isEqualTo(0.0);
        assertThat(index.risingBadge()).isFalse();
    }

    @Test
    void 구독자_10만_이상이면_미개척_점수를_받지_못한다() {
        ChannelMetrics m = new ChannelMetrics(30, 500_000, 2.0, 0.06, 0.5,
                2.0, 1.0, 1.5, 10, 2, 0.0, 3.0, 2.0);

        RisingIndex index = RisingIndexCalculator.calculate(m, 500_000, ScoringFixtures.defaultProps());

        assertThat(index.breakdown().get("untapped")).isEqualTo(0.0);
        assertThat(index.total()).isCloseTo(90.0, within(0.01));
    }

    @Test
    void 유료광고_이력이_5퍼센트_초과면_미개척_점수를_받지_못한다() {
        ChannelMetrics m = new ChannelMetrics(30, 100_000, 2.0, 0.06, 0.5,
                2.0, 1.0, 1.5, 10, 2, 0.1, 3.0, 2.0); // paidRatio 10%

        RisingIndex index = RisingIndexCalculator.calculate(m, 50_000, ScoringFixtures.defaultProps());

        assertThat(index.breakdown().get("untapped")).isEqualTo(0.0);
    }

    @Test
    void breakdown_합계는_total_과_일치한다() {
        ChannelMetrics m = new ChannelMetrics(30, 60_000, 1.2, 0.05, 0.5,
                1.5, 0.9, 1.2, 8, 4, 0.03, 2.0, 1.5);

        RisingIndex index = RisingIndexCalculator.calculate(m, 80_000, ScoringFixtures.defaultProps());

        double sum = index.breakdown().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(index.total()).isCloseTo(sum, within(1e-9));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :palim-automation:test --tests "*.RisingIndexCalculatorTest"`
Expected: FAIL (컴파일 오류)

- [ ] **Step 3: 구현**

`RisingIndex.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.Map;

/**
 * 라이징 지수 산출 결과. 캠페인과 무관한 전 풀 스캔용이다(스펙 §7).
 *
 * <p>{@code risingBadge} 가 참이면 매일 스냅샷 대상으로 승격되고 주간 텔레그램 알림에 실린다.
 */
public record RisingIndex(double total, Map<String, Double> breakdown, boolean risingBadge) {
}
```

`RisingIndexCalculator.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 라이징 지수 100점 — "곧 뜰 채널"의 선행 신호를 잡는다.
 *
 * <p>광고 단가는 구독자를 후행하고 조회수는 선행하므로, 조회수가 먼저 터진 채널은
 * 단가가 오르기 전의 차익 구간에 있다. 이 지수는 그 구간의 폭발 조짐 자체를 점수화한다.
 */
public final class RisingIndexCalculator {

    private RisingIndexCalculator() {
    }

    public static RisingIndex calculate(
            ChannelMetrics m, long subscriberCount, ScoringProperties props) {

        var rising = props.rising();
        Map<String, Double> breakdown = new LinkedHashMap<>();

        breakdown.put("vsrHeat", PiecewiseLinear.interpolate(rising.vsrHeatCurve(), m.vsr()));
        breakdown.put("accel", PiecewiseLinear.interpolate(rising.accelCurve(), m.trendRatio()));
        breakdown.put("velocity", PiecewiseLinear.interpolate(rising.velocityCurve(), m.velocityRatio()));
        breakdown.put("burst", PiecewiseLinear.interpolate(rising.burstCurve(), m.burstRatio()));

        boolean untapped = m.paidRatio() <= rising.untappedMaxPaidRatio()
                && subscriberCount < rising.untappedMaxSubscribers();
        breakdown.put("untapped", untapped ? rising.untappedPoints() : 0.0);

        double total = breakdown.values().stream().mapToDouble(Double::doubleValue).sum();
        return new RisingIndex(total, breakdown, total >= rising.badgeThreshold());
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :palim-automation:test --tests "*.RisingIndexCalculatorTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add palim-automation/
git commit -m "인플루언서 등급표, 라이징 레이더 설계 및 구현 : feat : 라이징 지수 계산기 구현 https://github.com/Cassiiopeia/palim/issues/41"
```

---

### Task 8: Grade + CpvEstimator

**Files:**
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/Grade.java`
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/CpvEstimate.java`
- Create: `palim-automation/src/main/java/kr/suhsaechan/palim/automation/influencer/scoring/CpvEstimator.java`
- Test: `palim-automation/src/test/java/kr/suhsaechan/palim/automation/influencer/scoring/GradeTest.java`
- Test: `palim-automation/src/test/java/kr/suhsaechan/palim/automation/influencer/scoring/CpvEstimatorTest.java`

**Interfaces:**
- Consumes: `ScoringProperties.GradeProps`, `ScoringProperties.CpvProps`
- Produces:
  - `enum Grade { S, A, B, C, D }` + `static Grade Grade.of(double total, ScoringProperties.GradeProps props)`
  - `record CpvEstimate(long estimatedPrice, double estimatedCpv)`
  - `CpvEstimate CpvEstimator.estimate(long subscriberCount, String categoryCode, double medianViews, ScoringProperties.CpvProps props)` — categoryCode 는 자체 분류 코드(null 허용 → default 계수)

- [ ] **Step 1: 실패하는 테스트 작성**

`GradeTest.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GradeTest {

    @Test
    void 등급_경계값이_스펙과_일치한다() {
        var props = ScoringFixtures.defaultProps().grade();
        assertThat(Grade.of(85.0, props)).isEqualTo(Grade.S);
        assertThat(Grade.of(84.9, props)).isEqualTo(Grade.A);
        assertThat(Grade.of(70.0, props)).isEqualTo(Grade.A);
        assertThat(Grade.of(55.0, props)).isEqualTo(Grade.B);
        assertThat(Grade.of(40.0, props)).isEqualTo(Grade.C);
        assertThat(Grade.of(39.9, props)).isEqualTo(Grade.D);
    }
}
```

`CpvEstimatorTest.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CpvEstimatorTest {

    private static final ScoringProperties.CpvProps PROPS =
            new ScoringProperties.CpvProps(25.0, Map.of("beauty", 40.0));

    @Test
    void 기본_계수로_추정_단가와_CPV_를_계산한다() {
        // 구독 10만 × 25원 = 250만원, 조회 중앙값 5만 → CPV 50원
        CpvEstimate e = CpvEstimator.estimate(100_000, null, 50_000, PROPS);

        assertThat(e.estimatedPrice()).isEqualTo(2_500_000L);
        assertThat(e.estimatedCpv()).isCloseTo(50.0, within(1e-9));
    }

    @Test
    void 카테고리_계수가_있으면_그것을_쓴다() {
        CpvEstimate e = CpvEstimator.estimate(100_000, "beauty", 50_000, PROPS);

        assertThat(e.estimatedPrice()).isEqualTo(4_000_000L);
    }

    @Test
    void 조회수가_0이면_CPV_는_무한대_대신_0으로_반환한다() {
        CpvEstimate e = CpvEstimator.estimate(100_000, null, 0, PROPS);

        assertThat(e.estimatedCpv()).isEqualTo(0.0);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :palim-automation:test --tests "*.GradeTest" --tests "*.CpvEstimatorTest"`
Expected: FAIL (컴파일 오류)

- [ ] **Step 3: 구현**

`Grade.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

/** 종합 등급. 룰 70 + AI 30 합산 총점 기준(AI 미심사 시 룰 점수만으로 잠정 등급). */
public enum Grade {
    S, A, B, C, D;

    public static Grade of(double total, ScoringProperties.GradeProps props) {
        if (total >= props.s()) {
            return S;
        }
        if (total >= props.a()) {
            return A;
        }
        if (total >= props.b()) {
            return B;
        }
        if (total >= props.c()) {
            return C;
        }
        return D;
    }
}
```

`CpvEstimate.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

/**
 * 추정 단가·CPV. 업계 관행 추정치라 점수에 섞지 않고 별도 컬럼으로만 노출한다(스펙 §5).
 * 실제 견적을 받으면 화면에서 이 값을 덮어쓴다.
 */
public record CpvEstimate(long estimatedPrice, double estimatedCpv) {
}
```

`CpvEstimator.java`:

```java
package kr.suhsaechan.palim.automation.influencer.scoring;

/** 추정 단가 = 구독자 × 카테고리 계수(원). 추정 CPV = 추정 단가 ÷ 롱폼 조회수 중앙값. */
public final class CpvEstimator {

    private CpvEstimator() {
    }

    public static CpvEstimate estimate(
            long subscriberCount, String categoryCode, double medianViews,
            ScoringProperties.CpvProps props) {

        double coefficient = categoryCode == null
                ? props.defaultCoefficient()
                : props.categoryCoefficients().getOrDefault(categoryCode, props.defaultCoefficient());
        long price = Math.round(subscriberCount * coefficient);
        double cpv = medianViews > 0 ? price / medianViews : 0.0;
        return new CpvEstimate(price, cpv);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :palim-automation:test --tests "*.GradeTest" --tests "*.CpvEstimatorTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: 전체 모듈 테스트 실행**

Run: `./gradlew :palim-automation:test :palim-common:test`
Expected: PASS (전체 — 회귀 확인)

- [ ] **Step 6: Commit**

```bash
git add palim-automation/
git commit -m "인플루언서 등급표, 라이징 레이더 설계 및 구현 : feat : 등급 매핑과 CPV 추정기 구현 https://github.com/Cassiiopeia/palim/issues/41"
```

---

### Task 9: push + CI 검증

**Files:** 없음 (검증만)

- [ ] **Step 1: push**

```bash
git push -u origin "20260811_#41_인플루언서_등급표_라이징_레이더_설계_및_구현"
```

- [ ] **Step 2: GitHub Actions 결과 확인**

CI 전체 빌드(+ guard 잡의 커밋 메시지 검사) 통과를 확인한다. 실패 시 로그를 보고 수정 커밋을 쌓는다 (로컬 Gradle 이 안 도는 환경에서는 이것이 1차 검증 지점이다 — 의도된 구조).
