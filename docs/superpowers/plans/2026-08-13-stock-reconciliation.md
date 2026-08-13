# 재고 정합성 대조 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 매일 자동으로 쌓이는 두 원천의 재고를 같은 기준으로 묶어 비교하고, 시간 탓으로 설명되지 않는 차이만 사람에게 보여준다.

**Architecture:** 새 모듈 `palim-reconcile` 이 표준 모델 테이블만 읽는다 — 원천이 API 인지 엑셀인지 모른다. 정합 단위(`reconcile_unit`)가 원천별 품목을 환산 계수로 묶고, 대조 정의가 무엇을 비교할지 데이터로 갖는다. 차이는 한 번 보고 확정하지 않고 두 번 관찰해 승격한다.

**Tech Stack:** Java 25 · Spring Boot 4.1 · PostgreSQL 14 · Flyway · JdbcClient · Thymeleaf + daisyUI · Testcontainers

## 전제가 하나 바뀌었다

설계 문서(3장)는 **「원천 API 직접 수집」을 이번 범위에서 뺐다.** 물류 원천 API 유료 결정이 안 났고 엑셀 업로드로 완주된다는 판단이었다.

**그 전제는 더 이상 유효하지 않다.** `HttpApiSourceReader` 와 `ConnectorScheduler` 가 붙어 두 원천 모두 매일 자동으로 `std_stock_snapshot` 에 쌓인다(#68). 따라서:

- 대조는 **사람이 엑셀을 올리는 것을 기다리지 않는다.** 쌓여 있는 스냅샷 위에서 돈다
- 기준일을 맞춰 다시 뽑는 일(설계 2.3)이 **화면 조작이 아니라 수집 재실행**이 된다
- 설계의 나머지 판단(정합 단위·승격·기준일 거부)은 그대로 유효하다

## Global Constraints

- PostgreSQL **14 문법만** — `NULLS NOT DISTINCT`(15+) · `MERGE`(15+) · `ANY_VALUE`(16+) 금지. 갱신은 `INSERT ... ON CONFLICT`
- 자연키 컬럼에 `COALESCE` 표현식 인덱스를 쓰지 않는다 — `StandardModelWriter` 가 `ON CONFLICT` 목록을 평문 조립하므로 적재 첫 실행에서 죽는다
- Testcontainers 는 `postgres:14-alpine` 고정
- `JdbcClient` 에 `Instant` 바인딩 불가 — `timestamptz` 에는 `OffsetDateTime`. `count(*)` 는 `bigint` 이므로 `record` 가 `int` 면 `count(*)::int`
- 전 계층 `Instant`, DB `timestamptz`. `LocalDateTime` 금지
- 예외는 `BusinessException` + `ErrorCode` 만. 새 실패 유형은 `ErrorCode` 한 줄 + `errors.properties`·`errors_en.properties` 각 한 줄
- 동결 도메인 수정 금지: `palim-sku` · `palim-order` · `palim-collector` · `palim-channel` · `palim-mapping` · `palim-incident`
- `palim-reconcile` 은 `palim-connector` 를 의존하지 않는다. 둘은 표준 모델 테이블로만 만난다
- 공개 저장소 — 발주사 상호·품목명·회사코드, 서버 호스트·공인 IP 를 코드·주석·테스트·커밋 메시지 어디에도 쓰지 않는다. 테스트는 합성값(`제품A`, `123456`)
- 커밋 메시지에 AI 흔적 금지. 형식은 `{이슈제목} : {타입} : {설명} {이슈URL}` — `/pro-commit` 사용
- 빌드: `JAVA_HOME=<JDK21> ./gradlew build -Porg.gradle.java.installations.paths=<JDK25>`
- 기존 419건 통과 유지

## 파일 구조

```
palim-reconcile/
  unit/        ReconcileUnit · ReconcileUnitMember · 저장소       정합 단위
  rule/        NormalizationRule · NormalizationEngine            품명 정규화
  define/      ReconcileDefinition · 저장소                       무엇을 비교할지
  run/         ReconcileRun · ReconcileDiff · DiffType · DiffState  실행과 차이
  engine/      SnapshotAggregator · ReconcileEngine · Promoter    합산·비교·승격
  match/       MatchCandidateFinder                               매칭 후보 제안
```

한 파일이 한 가지만 안다. `SnapshotAggregator` 는 SQL 만, `ReconcileEngine` 은 비교 규칙만, `Promoter` 는 승격 판정만 안다. 셋을 한 클래스에 두면 «허용 오차를 고쳤는데 승격이 깨지는» 일이 생긴다.

---

### Task 1: 모듈 만들기와 테이블

**Files:**
- Modify: `settings.gradle.kts`
- Create: `palim-reconcile/build.gradle.kts`
- Create: `palim-app/src/main/resources/db/migration/V20__reconcile.sql`
- Modify: `palim-app/build.gradle.kts` (테스트 의존성)
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/ReconcileSchemaIntegrationTest.java`

**Interfaces:**
- Produces: 테이블 6개 — `reconcile_unit` · `reconcile_unit_member` · `normalization_rule` · `reconcile_definition` · `reconcile_run` · `reconcile_diff`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 대조 테이블이 PostgreSQL 14 에서 만들어지는가.
 *
 * <p>운영 DB 가 14 이고 상위 버전 전용 문법은 <b>배포에서만</b> 죽는다. 실제로
 * {@code NULLS NOT DISTINCT} 로 한 번 겪었다.
 */
class ReconcileSchemaIntegrationTest extends IntegrationTest {

    @Autowired private JdbcClient jdbcClient;

    @Test
    @DisplayName("대조 테이블 여섯 개가 만들어진다")
    void 테이블이_만들어진다() {
        int found = jdbcClient.sql("""
                        SELECT count(*)::int FROM information_schema.tables
                         WHERE table_name IN ('reconcile_unit','reconcile_unit_member',
                               'normalization_rule','reconcile_definition',
                               'reconcile_run','reconcile_diff')
                        """)
                .query(Integer.class).single();

        assertThat(found).isEqualTo(6);
    }

    /**
     * 한 품목이 두 단위에 붙으면 그 수량이 두 번 세어지고 <b>대조 결과가 조용히 틀린다.</b>
     * 화면 검증만으로는 동시 요청에서 뚫리므로 DB 가 막아야 한다.
     */
    @Test
    @DisplayName("같은 원천 품목은 한 단위에만 속한다")
    void 품목_중복_연결을_막는다() {
        int unique = jdbcClient.sql("""
                        SELECT count(*)::int FROM pg_indexes
                         WHERE tablename = 'reconcile_unit_member'
                           AND indexdef LIKE '%UNIQUE%'
                           AND indexdef LIKE '%source%'
                           AND indexdef LIKE '%item_ref%'
                        """)
                .query(Integer.class).single();

        assertThat(unique).isGreaterThanOrEqualTo(1);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :palim-app:test --tests "*ReconcileSchemaIntegrationTest*"`
Expected: FAIL — 테이블이 없어 `found` 가 0

- [ ] **Step 3: 모듈 등록**

`settings.gradle.kts` 의 「연동」 블록 아래에 더한다:

```kotlin
// 대조 — 표준 모델 위에서 두 원천을 비교한다. 원천이 무엇인지 모른다.
include("palim-reconcile")
```

`palim-reconcile/build.gradle.kts`:

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

`palim-app/build.gradle.kts` 에 두 줄 더한다 — 도메인 모듈은 `implementation` 이라 테스트 classpath 로 전이되지 않는다:

```kotlin
    implementation(project(":palim-reconcile"))
```
그리고 테스트 블록에:
```kotlin
    testImplementation(project(":palim-reconcile"))
```

- [ ] **Step 4: 마이그레이션 작성**

`V20__reconcile.sql`:

```sql
-- 정합 단위 — 대조의 기본 단위.
--
-- 같은 물건이 원천마다 다른 개수로 잡힌다. 전산은 「1박스」로, 물류는 「낱개 12개」로 센다.
-- 그 둘을 같은 것으로 보려면 «무엇을 하나로 볼지» 를 사람이 정해야 한다.
CREATE TABLE reconcile_unit
(
    id         uuid         NOT NULL,
    tenant_id  uuid         NOT NULL,
    code       varchar(100) NOT NULL,
    name       varchar(200) NOT NULL,
    base_unit  varchar(20)  NOT NULL DEFAULT 'EA',
    is_active  boolean      NOT NULL DEFAULT true,
    created_at timestamptz,
    updated_at timestamptz,
    CONSTRAINT pk_reconcile_unit PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_reconcile_unit_code ON reconcile_unit (tenant_id, code);

-- 원천 품목이 어느 단위에 속하나.
--
-- factor 가 세트 상품을 흡수한다. 「1세트 = 본품 2 + 사은품 1」과 「전산 1품목 = 물류 3품목」이
-- 같은 구조가 되므로 별도 세트 기능을 만들지 않는다.
CREATE TABLE reconcile_unit_member
(
    id           uuid           NOT NULL,
    tenant_id    uuid           NOT NULL,
    unit_id      uuid           NOT NULL,
    source       varchar(50)    NOT NULL,
    item_ref     varchar(255)   NOT NULL,
    factor       numeric(19, 6) NOT NULL DEFAULT 1,
    -- 비어 있으면 «제안» 이다. 사람이 확인하지 않은 추측으로 재고를 합산하면
    -- 그 결과가 맞는지 아무도 모른다.
    confirmed_at timestamptz,
    created_at   timestamptz,
    updated_at   timestamptz,
    CONSTRAINT pk_reconcile_unit_member PRIMARY KEY (id)
);
-- 한 품목이 두 단위에 붙으면 수량이 두 번 세어져 «대조 결과가 조용히 틀린다».
-- 세 컬럼 모두 NOT NULL 이라 PG14 에서도 평범한 유니크로 충분하다.
CREATE UNIQUE INDEX ux_reconcile_member_item
    ON reconcile_unit_member (tenant_id, source, item_ref);
CREATE INDEX ix_reconcile_member_unit ON reconcile_unit_member (tenant_id, unit_id);

-- 품명 정규화 규칙. 매칭 후보를 좁히는 데만 쓰고 확정하지 않는다.
CREATE TABLE normalization_rule
(
    id          uuid         NOT NULL,
    tenant_id   uuid         NOT NULL,
    name        varchar(200) NOT NULL,
    pattern     varchar(500) NOT NULL,
    replacement varchar(200) NOT NULL DEFAULT '',
    sort_order  integer      NOT NULL DEFAULT 0,
    is_active   boolean      NOT NULL DEFAULT true,
    created_at  timestamptz,
    updated_at  timestamptz,
    CONSTRAINT pk_normalization_rule PRIMARY KEY (id)
);
CREATE INDEX ix_normalization_rule_order ON normalization_rule (tenant_id, sort_order);

-- 무엇을 비교할지.
--
-- compare_field 를 정의로 받기 때문에 금액 대조나 가용수량 대조로 바꿔도 코드를 고치지 않는다.
CREATE TABLE reconcile_definition
(
    id              uuid           NOT NULL,
    tenant_id       uuid           NOT NULL,
    code            varchar(100)   NOT NULL,
    name            varchar(200)   NOT NULL,
    left_source     varchar(50)    NOT NULL,
    right_source    varchar(50)    NOT NULL,
    target_table    varchar(100)   NOT NULL DEFAULT 'std_stock_snapshot',
    compare_field   varchar(100)   NOT NULL DEFAULT 'base_quantity',
    tolerance       numeric(19, 3) NOT NULL DEFAULT 0,
    -- 비어 있으면 알리지 않는다. 매일 도는 일이 매일 알림을 보내면 아무도 안 본다.
    alert_threshold numeric(19, 3),
    is_active       boolean        NOT NULL DEFAULT true,
    created_at      timestamptz,
    updated_at      timestamptz,
    CONSTRAINT pk_reconcile_definition PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_reconcile_definition_code ON reconcile_definition (tenant_id, code);

CREATE TABLE reconcile_run
(
    id              uuid        NOT NULL,
    tenant_id       uuid        NOT NULL,
    definition_id   uuid        NOT NULL,
    -- 양쪽 스냅샷이 «공유하는» 시각. 다르면 비교 자체를 거부한다.
    base_at         timestamptz NOT NULL,
    status          varchar(20) NOT NULL,
    left_count      integer     NOT NULL DEFAULT 0,
    right_count     integer     NOT NULL DEFAULT 0,
    diff_count      integer     NOT NULL DEFAULT 0,
    unmatched_count integer     NOT NULL DEFAULT 0,
    started_at      timestamptz NOT NULL,
    finished_at     timestamptz,
    message         varchar(1000),
    created_at      timestamptz,
    updated_at      timestamptz,
    CONSTRAINT pk_reconcile_run PRIMARY KEY (id)
);
CREATE INDEX ix_reconcile_run_definition
    ON reconcile_run (tenant_id, definition_id, started_at DESC);

CREATE TABLE reconcile_diff
(
    id                uuid           NOT NULL,
    tenant_id         uuid           NOT NULL,
    run_id            uuid           NOT NULL,
    -- 비어 있으면 미매칭 — 아직 어느 단위에도 속하지 않은 품목이다.
    unit_id           uuid,
    unit_code         varchar(100)   NOT NULL DEFAULT '',
    left_quantity     numeric(19, 3) NOT NULL DEFAULT 0,
    right_quantity    numeric(19, 3) NOT NULL DEFAULT 0,
    delta             numeric(19, 3) NOT NULL DEFAULT 0,
    diff_type         varchar(20)    NOT NULL,
    state             varchar(20)    NOT NULL,
    action_status     varchar(20)    NOT NULL DEFAULT 'UNCHECKED',
    action_note       varchar(1000),
    -- 이 차이가 처음 관찰된 실행. 승격 판정의 근거다.
    first_seen_run_id uuid,
    created_at        timestamptz,
    updated_at        timestamptz,
    CONSTRAINT pk_reconcile_diff PRIMARY KEY (id)
);
CREATE INDEX ix_reconcile_diff_run ON reconcile_diff (tenant_id, run_id);
CREATE INDEX ix_reconcile_diff_unit ON reconcile_diff (tenant_id, unit_id, diff_type);
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :palim-app:test --tests "*ReconcileSchemaIntegrationTest*"`
Expected: PASS 2건

- [ ] **Step 6: 전체 회귀 후 커밋**

Run: `./gradlew build` → `/pro-commit`, 타입 `feat`, 설명 "대조 모듈과 테이블 추가"

---

### Task 2: 정합 단위와 원천 품목 연결

**Files:**
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/unit/ReconcileUnit.java`
- Create: `palim-reconcile/.../unit/ReconcileUnitMember.java`
- Create: `palim-reconcile/.../unit/ReconcileUnitRepository.java`
- Create: `palim-reconcile/.../unit/ReconcileUnitMemberRepository.java`
- Create: `palim-reconcile/.../unit/ReconcileUnitService.java`
- Test: `palim-app/src/test/java/.../ReconcileUnitIntegrationTest.java`

**Interfaces:**
- Produces:
  - `ReconcileUnit.of(UUID tenantId, String code, String name, String baseUnit)`
  - `ReconcileUnitMember.of(UUID tenantId, UUID unitId, String source, String itemRef, BigDecimal factor)` — `confirmedAt` 은 비어 있다
  - `ReconcileUnitMember.confirm()` — 확정 시각을 찍는다
  - `ReconcileUnitService.propose(UUID unitId, String source, String itemRef, BigDecimal factor)`
  - `ReconcileUnitService.confirm(UUID memberId)`
- Consumes: Task 1 의 테이블

- [ ] **Step 1: 실패하는 테스트 작성**

```java
@Test
@DisplayName("확정하기 전에는 대조에 쓰이지 않는다")
void 제안은_대조에_쓰이지_않는다() {
    ReconcileUnit unit = unitService.create("UNIT-A", "제품A 227g", "EA");
    ReconcileUnitMember member = unitService.propose(
            unit.getId(), "erp", "A0001", new BigDecimal("1"));

    assertThat(member.getConfirmedAt())
            .as("사람이 확인하지 않은 추측으로 재고를 합산하면 결과가 맞는지 아무도 모른다")
            .isNull();

    unitService.confirm(member.getId());

    assertThat(memberRepository.findById(member.getId()).orElseThrow().getConfirmedAt())
            .isNotNull();
}

@Test
@DisplayName("한 품목을 두 단위에 붙이면 거부한다")
void 품목은_한_단위에만_속한다() {
    ReconcileUnit first = unitService.create("UNIT-A", "제품A", "EA");
    ReconcileUnit second = unitService.create("UNIT-B", "제품B", "EA");
    unitService.propose(first.getId(), "erp", "A0001", BigDecimal.ONE);

    assertThatThrownBy(() ->
            unitService.propose(second.getId(), "erp", "A0001", BigDecimal.ONE))
            .as("두 단위에 붙으면 그 수량이 두 번 세어져 결과가 조용히 틀린다")
            .isInstanceOf(BusinessException.class);
}
```

- [ ] **Step 2: 실패 확인** → **Step 3: 엔티티·저장소·서비스 작성**

`ReconcileUnitMember` 는 `@Filter` 로 테넌트 격리한다(`ConnectorSecret` 과 같은 방식). `propose` 는 저장 전에 `findBySourceAndItemRef` 로 확인하고, 있으면 `BusinessException(ErrorCode.INVALID_INPUT, ...)` 를 던진다. **DB 유니크가 최종 방어선이고 이 검사는 사람에게 이유를 알려주는 몫이다.**

- [ ] **Step 4: 통과 확인 후 커밋** — 타입 `feat`, 설명 "정합 단위와 원천 품목 연결 추가"

---

### Task 3: 스냅샷 합산

**Files:**
- Create: `palim-reconcile/.../engine/SnapshotAggregator.java`
- Test: `palim-app/src/test/java/.../SnapshotAggregatorIntegrationTest.java`

**Interfaces:**
- Produces: `Map<UUID, BigDecimal> sumByUnit(UUID tenantId, String source, Instant baseAt, String compareField)`
- Consumes: Task 2 의 `reconcile_unit_member`

- [ ] **Step 1: 실패하는 테스트 작성**

환산 계수가 곱해지는지, 확정 안 된 연결이 빠지는지 확인한다.

```java
@Test
@DisplayName("환산 계수를 곱해 단위별로 합산한다")
void 환산해서_합산한다() {
    // 물류는 낱개로 12개, 전산은 박스로 1개. factor 12 로 묶으면 같은 수량이 된다.
    // (준비 코드는 Task 2 의 서비스로 단위와 확정 연결을 만든다)
    Map<UUID, BigDecimal> sums = aggregator.sumByUnit(
            TENANT, "wms", baseAt, "base_quantity");

    assertThat(sums.get(unitId)).isEqualByComparingTo("12");
}

@Test
@DisplayName("확정하지 않은 연결은 합산에서 뺀다")
void 제안은_합산하지_않는다() {
    // propose 만 하고 confirm 하지 않은 상태
    Map<UUID, BigDecimal> sums = aggregator.sumByUnit(
            TENANT, "erp", baseAt, "base_quantity");

    assertThat(sums)
            .as("확인 안 한 추측으로 합산하면 결과가 맞는지 아무도 모른다")
            .isEmpty();
}
```

- [ ] **Step 2: 실패 확인** → **Step 3: 구현**

```java
/**
 * 확정된 정합 단위로 스냅샷을 합산한다.
 *
 * <p>{@code confirmed_at IS NOT NULL} 이 빠지면 <b>사람이 확인하지 않은 추측으로 재고를
 * 합산</b>하게 되고, 그 결과가 맞는지 아무도 모른다.
 *
 * <p>{@code compareField} 를 문자열로 받지만 <b>정의에 등록된 값만</b> 온다. 화면이 임의
 * 문자열을 넘길 수 없도록 호출자가 막고, 여기서도 허용 목록으로 한 번 더 거른다 — SQL 에
 * 이름을 끼워 넣는 자리라 뚫리면 조회 범위가 통째로 열린다.
 */
public Map<UUID, BigDecimal> sumByUnit(UUID tenantId, String source, Instant baseAt,
                                       String compareField) {
    String column = ALLOWED_FIELDS.contains(compareField) ? compareField : "base_quantity";
    return jdbcClient.sql("""
                    SELECT m.unit_id AS unitId, sum(s.%s * m.factor) AS qty
                      FROM std_stock_snapshot s
                      JOIN reconcile_unit_member m
                        ON m.tenant_id = s.tenant_id
                       AND m.source    = s.source
                       AND m.item_ref  = s.item_ref
                       AND m.confirmed_at IS NOT NULL
                     WHERE s.tenant_id = :tenantId
                       AND s.source    = :source
                       AND s.base_at   = :baseAt
                     GROUP BY m.unit_id
                    """.formatted(column))
            .param("tenantId", tenantId)
            .param("source", source)
            // JdbcClient 는 Instant 를 바인딩하지 못한다. timestamptz 에는 OffsetDateTime.
            .param("baseAt", baseAt.atOffset(ZoneOffset.UTC))
            .query((rs, n) -> Map.entry(rs.getObject("unitId", UUID.class),
                    rs.getBigDecimal("qty")))
            .list().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
}

private static final Set<String> ALLOWED_FIELDS = Set.of(
        "base_quantity", "quantity", "available_quantity", "amount");
```

- [ ] **Step 4: 통과 확인 후 커밋** — 타입 `feat`, 설명 "확정된 정합 단위로 스냅샷 합산"

---

### Task 4: 기준일 거부

**Files:**
- Create: `palim-reconcile/.../engine/BaseAtResolver.java`
- Modify: `palim-common/.../error/ErrorCode.java` (+`RECONCILE_BASE_AT_MISMATCH`)
- Modify: `palim-common/src/main/resources/errors.properties` · `errors_en.properties`
- Test: `palim-app/src/test/java/.../BaseAtResolverIntegrationTest.java`

**Interfaces:**
- Produces: `Instant resolve(UUID tenantId, String leftSource, String rightSource)` — 양쪽 최신 기준일이 다르면 던진다

- [ ] **Step 1: 실패하는 테스트**

```java
@Test
@DisplayName("기준일이 다르면 비교를 거부한다")
void 기준일이_다르면_거부한다() {
    // 좌측은 오늘, 우측은 어제 스냅샷만 있는 상태
    assertThatThrownBy(() -> resolver.resolve(TENANT, "erp", "wms"))
            .as("두 재고를 다른 시각에 뽑으면 그 사이 출고분만큼 무조건 차이가 난다")
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e)
                    .is(ErrorCode.RECONCILE_BASE_AT_MISMATCH)).isTrue());
}
```

- [ ] **Step 2~4: 구현**

`ErrorCode` 한 줄:
```java
RECONCILE_BASE_AT_MISMATCH("R001", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.WARN),
```
`errors.properties`:
```properties
error.RECONCILE_BASE_AT_MISMATCH=양쪽 재고의 기준 시각이 다릅니다({0} · {1}). 같은 시각으로 다시 받아온 뒤 비교하세요.
```
`errors_en.properties`:
```properties
error.RECONCILE_BASE_AT_MISMATCH=The two snapshots have different base times ({0} · {1}). Collect both again for the same time, then compare.
```

**억지로 맞춰 비교하지 않는다.** 그 차이가 진짜인지 시간 탓인지 영영 알 수 없고, 그런 결과는 몇 번 어긋나는 순간 아무도 보지 않게 된다. 대조가 신뢰를 잃는 것이 대조가 없는 것보다 나쁘다 — 있는데 아무도 안 보는 화면이 되면 문제가 있다는 사실 자체가 가려진다.

- [ ] **Step 5: 커밋** — 타입 `feat`, 설명 "기준 시각이 다른 재고는 비교를 거부"

---

### Task 5: 대조 엔진과 승격

**Files:**
- Create: `palim-reconcile/.../define/ReconcileDefinition.java` · 저장소
- Create: `palim-reconcile/.../run/ReconcileRun.java` · `ReconcileDiff.java` · `DiffType.java` · `DiffState.java` · 저장소들
- Create: `palim-reconcile/.../engine/ReconcileEngine.java`
- Create: `palim-reconcile/.../engine/DiffPromoter.java`
- Test: `palim-app/src/test/java/.../ReconcileEngineIntegrationTest.java`

**Interfaces:**
- Consumes: `SnapshotAggregator.sumByUnit(...)` (Task 3) · `BaseAtResolver.resolve(...)` (Task 4)
- Produces: `ReconcileRun run(UUID definitionId)`
- `enum DiffType { LEFT_MORE, RIGHT_MORE, UNMATCHED_LEFT, UNMATCHED_RIGHT }`
- `enum DiffState { OBSERVING, CONFIRMED, RESOLVED, IGNORED }`

- [ ] **Step 1: 실패하는 테스트 — 네 가지**

```java
@Test
@DisplayName("허용 오차 이내는 차이로 남기지 않는다")
void 허용_오차_이내는_넘어간다() { }

@Test
@DisplayName("처음 본 차이는 관찰중이다")
void 처음_본_차이는_관찰중이다() {
    ReconcileRun run = engine.run(definitionId);

    assertThat(diffOf(run).getState())
            .as("반영 지연일 수 있다. 첫 회차에 알리면 매일 헛알림이 간다")
            .isEqualTo(DiffState.OBSERVING);
}

@Test
@DisplayName("다음 실행에도 같은 방향이면 확정으로 올린다")
void 두_번_보이면_확정한다() {
    engine.run(definitionId);
    ReconcileRun second = engine.run(definitionId);

    assertThat(diffOf(second).getState())
            .as("시간으로 설명되지 않는 차이만 사람에게 알린다")
            .isEqualTo(DiffState.CONFIRMED);
}

@Test
@DisplayName("어느 단위에도 없는 품목은 미매칭으로 남긴다")
void 미매칭을_기록한다() {
    ReconcileRun run = engine.run(definitionId);

    assertThat(diffsOf(run))
            .as("매칭 안 된 품목 하나 때문에 대조 전체를 멈추면 나머지 결과도 못 본다")
            .anyMatch(d -> d.getDiffType() == DiffType.UNMATCHED_LEFT);
}
```

- [ ] **Step 2: 실패 확인** → **Step 3: 엔진 구현**

흐름은 설계 6장 그대로다. 승격 판정은 `DiffPromoter` 가 맡는다 — 직전 실행에서 **같은 단위·같은 방향** 차이를 찾고, 있으면 `CONFIRMED` 로 올리며 `first_seen_run_id` 를 물려받는다.

**미매칭은 실패가 아니라 결과의 한 유형이다.** 매칭 안 된 품목 하나 때문에 대조 전체를 중단하면 나머지 결과도 못 보게 되고, 그러면 사람이 매칭을 끝낼 때까지 대조를 아예 못 쓴다.

- [ ] **Step 4: 통과 확인 후 커밋** — 타입 `feat`, 설명 "대조 실행과 관찰중에서 확정으로의 승격"

---

### Task 6: 정규화와 매칭 후보 제안

**Files:**
- Create: `palim-reconcile/.../rule/NormalizationRule.java` · 저장소 · `NormalizationEngine.java`
- Create: `palim-reconcile/.../match/MatchCandidateFinder.java`
- Test: `palim-reconcile/src/test/java/.../NormalizationEngineTest.java` · `palim-app/src/test/java/.../MatchCandidateIntegrationTest.java`

**Interfaces:**
- Produces:
  - `String normalize(String rawName)` — 규칙을 `sortOrder` 순으로 적용
  - `List<MatchCandidate> suggest(UUID tenantId, String leftSource, String rightSource)`
  - `record MatchCandidate(String normalizedName, List<SourceItem> items)`
  - `record SourceItem(String source, String itemRef, String rawName)`

- [ ] **Step 1: 실패하는 테스트**

```java
@Test
@DisplayName("규칙을 순서대로 적용해 품명을 정규화한다")
void 순서대로_정규화한다() {
    // 「제품A 16g (26.11.07)」 에서 괄호를 떼고 공백을 정리한다
    assertThat(engine.normalize("제품A 16g (26.11.07)")).isEqualTo("제품A16g");
}

@Test
@DisplayName("정규화 결과가 같은 것끼리 후보로 묶는다")
void 같은_이름끼리_묶는다() {
    List<MatchCandidate> candidates = finder.suggest(TENANT, "erp", "wms");

    assertThat(candidates)
            .as("후보를 좁힐 뿐 확정하지 않는다 — 규칙이 틀리면 엉뚱한 품목을 합쳐 놓고 «맞는다» 고 보고한다")
            .anySatisfy(c -> assertThat(c.items()).hasSize(2));
}
```

- [ ] **Step 2~4: 구현**

정규화는 **후보를 좁힐 뿐 확정하지 않는다.** 규칙이 틀리면 엉뚱한 품목을 합쳐 놓고 "재고가 맞는다"고 보고하는데, 이건 불일치를 못 찾는 것보다 나쁘다 — 틀렸다는 사실조차 드러나지 않는다.

원본 품명은 `std_stock_snapshot.raw_item_name` 에 이미 보존되어 있어 규칙을 바꾼 뒤 다시 계산할 수 있다.

- [ ] **Step 5: 커밋** — 타입 `feat`, 설명 "품명 정규화와 매칭 후보 제안"

---

### Task 7: 화면 — 대조 실행과 결과

**Files:**
- Create: `palim-web/.../reconcile/ReconcileController.java` · `ReconcileAdminService.java` · `DiffRowView.java`
- Create: `palim-web/src/main/resources/templates/reconcile/runs.html` · `run-detail.html`
- Modify: `palim-web/src/main/resources/templates/layout.html` (메뉴 한 줄)
- Test: `palim-app/src/test/java/.../ReconcileScreenRenderIntegrationTest.java`

- [ ] **Step 1: 렌더링 테스트** — 차이 목록에 단위명·좌우 수량·차이가 보이는지. 템플릿 표현식 오류는 컴파일에 걸리지 않고 화면을 여는 순간 터진다

- [ ] **Step 2~4: 구현** — daisyUI 만 쓴다(07-DECISIONS 009). 숫자는 `tabular-nums` 로 자릿수를 맞춘다. 확정 차이를 위에, 관찰중을 아래에 둔다 — 지금 손댈 것과 지켜볼 것을 섞으면 둘 다 안 보게 된다

- [ ] **Step 5: 커밋** — 타입 `feat`

---

### Task 8: 화면 — 정합 단위와 매칭

**Files:**
- Create: `palim-web/.../reconcile/UnitController.java`
- Create: `palim-web/src/main/resources/templates/reconcile/units.html`
- Test: `palim-app/src/test/java/.../UnitScreenRenderIntegrationTest.java`

- [ ] **Step 1: 렌더링 테스트** — 제안과 확정이 구분되어 보이는지
- [ ] **Step 2~4: 구현** — 후보를 보여주고 사람이 확정한다. **제안 상태를 확정처럼 보이게 하면 안 된다** — 확인하지 않은 것이 확인된 것처럼 보이면 아무도 확인하지 않는다
- [ ] **Step 5: 커밋** — 타입 `feat`

---

### Task 9: 임계 초과 알림

**Files:**
- Create: `palim-reconcile/.../engine/ReconcileAlerter.java`
- Test: `palim-app/src/test/java/.../ReconcileAlertIntegrationTest.java`

- [ ] **Step 1: 실패하는 테스트** — 관찰중은 알리지 않고 확정만 알리는지 · 임계 미만이면 알리지 않는지
- [ ] **Step 2~4: 구현** — `palim-notification` 을 쓴다. **관찰중은 알리지 않는다.** 반영 지연일 수 있는 것까지 알리면 매일 헛알림이 가고, 그러면 진짜 알림도 안 보게 된다
- [ ] **Step 5: 커밋** — 타입 `feat`

---

### Task 10: 매일 자동 대조

**Files:**
- Create: `palim-reconcile/.../engine/ReconcileScheduler.java`
- Test: `palim-app/src/test/java/.../ReconcileSchedulerIntegrationTest.java`

- [ ] **Step 1: 실패하는 테스트** — 수집이 끝난 뒤 도는지 · 기준일이 어긋나면 실행이 `FAILED` 로 남고 다음 회차에 다시 시도하는지
- [ ] **Step 2~4: 구현** — 수집 스케줄러(`ConnectorScheduler`)보다 늦게 돈다. 순서가 뒤집히면 어제 자료로 대조한다
- [ ] **Step 5: 커밋** — 타입 `feat`

---

## Self-Review

**1. 설계 대응** — 정합 단위(T2) · 정규화·후보(T6) · 대조 정의·실행·차이 분류(T5) · 승격(T5) · 조치 상태(T5 스키마, T7 화면) · 화면 셋(T7·T8) · 임계 알림(T9) 전부 대응됨. 설계의 「이번에 하지 않는 것」(발주서 출력 · 재고 예측)은 계획에도 없다.

**2. 전제 변경 반영** — 설계가 뺐던 「원천 API 직접 수집」이 이미 완료됐으므로 T10(자동 대조)을 더했다. 설계에는 없던 작업이지만, 수집이 자동인데 대조만 수동이면 사람이 매일 버튼을 눌러야 한다.

**3. 미완 항목** — T7·T8·T9·T10 은 단계 서술이 T1~T6 보다 성기다. 앞 작업이 끝나야 실제 시그니처가 확정되므로 **각 작업 착수 시점에 그 작업만 상세화**한다. 상세화 없이 바로 구현하지 않는다.

**4. 타입 일관성** — `sumByUnit(...)`(T3)은 T5 가 소비한다. `DiffType`·`DiffState`(T5)는 T7 화면과 T9 알림이 쓴다. `MatchCandidate`(T6)는 T8 화면이 쓴다.

**5. 순서** — T1(테이블)→T2(단위)→T3(합산)→T4(기준일)→T5(엔진)이 뼈대다. T6 은 T5 와 독립이라 순서를 바꿔도 되지만, T8 화면은 T6 을 필요로 한다.
