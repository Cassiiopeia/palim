# 대조 조건 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 대조가 무엇을 볼지 **어느 칸으로든** 거를 수 있게 하고, 지금 걸린 조건을 화면이 말하게 한다.

**Architecture:** 조건 줄(드롭다운)과 식(자유 입력)이 **하나의 AST** 로 모이고, SQL 은 그 AST 에서만 생성된다. 사용자 문자열이 SQL 에 이어붙는 자리가 없다. 기존 `WarehouseScope` 를 `FilterSpec` 으로 대체하고 창고 설정은 조건 줄 한 줄로 이관한다.

**Tech Stack:** Java 25 · Spring Boot 4 · JdbcClient · Hibernate · Flyway · PostgreSQL **14** · Thymeleaf + daisyUI · Testcontainers · JUnit5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-08-22-reconcile-filters-design.md`

**이슈:** https://github.com/Cassiiopeia/palim/issues/161
**브랜치:** `20260822_#161_창고_말고_다른_조건으로는_대조를_좁힐_수가_없다`

## Global Constraints

- **PostgreSQL 14 문법만.** `NULLS NOT DISTINCT`·`MERGE`·`ANY_VALUE`·`JSON_TABLE` 금지. `INSERT ... ON CONFLICT` 를 쓴다
- **`Instant` 만.** `LocalDateTime` 금지. `JdbcClient` 의 `timestamptz` 바인딩은 `OffsetDateTime` 으로 변환해 넘긴다
- **예외는 `BusinessException` + `ErrorCode`.** 새 예외 클래스 금지. `ErrorCode` 한 줄 + `errors.properties`/`errors_en.properties` 각 한 줄
- **커밋 메시지 형식:** `창고 말고 다른 조건으로는 대조를 좁힐 수가 없다 : {타입} : {설명} https://github.com/Cassiiopeia/palim/issues/161`
- **AI 흔적 금지.** `Co-Authored-By`·`Generated with`·🤖 일절 금지 (CI `guard` 잡이 커밋 메시지를 검사한다)
- **발주사·인프라 식별정보 금지.** 상호·브랜드·제품명·호스트·계정명을 코드·주석·테스트·커밋에 쓰지 않는다. 시험 자료는 합성으로
- **동결 도메인 금지:** `palim-sku`·`palim-order`·`palim-collector`·`palim-channel`·`palim-mapping`·`palim-incident`
- **마이그레이션 번호:** 현재 최신은 `V33__connector_cascade.sql`. 이 계획은 `V34`·`V35` 를 쓴다
- **업무 시간대는 `Asia/Seoul`** (`BaseAtGranularity.BUSINESS_ZONE` 과 같은 값)
- **빌드:** `./gradlew build`. 로컬에서 Gradle 배포판 내려받기가 막히면 push 후 GitHub Actions 결과로 검증한다

---

## File Structure

새 패키지 `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/` 하나에 모은다. 조건은 «정의» 도 «엔진» 도 아닌 별개 관심사이고, `define/` 에 넣으면 그 패키지가 정의·비교칸·창고·조건을 다 들고 있게 된다.

| 파일 | 책임 |
|---|---|
| `filter/FieldType.java` | 칸의 값 종류. 어떤 연산자를 쓸 수 있는지가 여기서 갈린다 |
| `filter/FilterableField.java` | 걸 수 있는 칸 하나. 키·이름·타입·SQL 표현식 |
| `filter/FieldCatalog.java` | 표준 칸 목록과 조회. **카탈로그에 없는 키는 SQL 이 되지 않는다** |
| `filter/FilterOperator.java` | 연산자. 값 개수 규칙·허용 타입·SQL 조각 |
| `filter/DateToken.java` | `오늘`·`오늘+30`·`2026-08-22` 를 읽고 기준 시각으로 푼다 |
| `filter/FilterSql.java` | SQL 조각과 바인딩을 함께 쌓는 그릇. 이름 충돌을 구조로 막는다 |
| `filter/FilterNode.java` | AST. `All`·`And`·`Or`·`Not`·`Compare` |
| `filter/FilterSpec.java` | AST 한 그루를 감싸 조회에 넘기는 값 객체. `WarehouseScope` 를 대체 |
| `filter/FilterRow.java` | 저장된 조건 줄 하나 (엔티티) |
| `filter/FilterRowRepository.java` | 조건 줄 조회 |
| `filter/FilterSide.java` | `LEFT`/`RIGHT` |
| `filter/FilterCompiler.java` | 저장된 줄·식 → `FilterSpec` |
| `filter/ExpressionParser.java` | 식 → AST. 문법에 없는 것은 읽지 않는다 |
| `filter/ExpressionWriter.java` | AST → 사람이 읽는 식 |
| `filter/FilterService.java` | 조건 읽기·저장·검증의 입구 |
| `engine/SnapshotAggregator.java` | (수정) `WarehouseScope` → `FilterSpec`, 값 후보·미리보기 조회 추가 |
| `match/MatchBoard.java` | (수정) 좌·우 조건을 다른 접두어로 바인딩 |
| `define/Pairing.java` | (수정) 창고 대신 `FilterSpec` 을 든다 |
| `web/reconcile/FilterController.java` | 조건 편집 화면의 POST 들 |
| `templates/reconcile/detail.html` | (수정) 「견줄 창고」 → 「볼 조건」 |
| `templates/reconcile/units.html` | (수정) 지금 걸린 조건 표시 + 고치러 가는 길 |

`WarehouseScope.java` 는 **Task 5 에서 삭제**한다. 남겨 두면 두 벌이 되어 한쪽만 고쳐지는 날이 온다.

## 과제 순서

```
   1 카탈로그 ─▶ 2 연산자·날짜 ─▶ 3 AST·SQL ─┬─▶ 4 저장·이관 ─▶ 5 조회 연결 ─▶ 6 회차
                                             └─▶ 7 식 파서 ─────────────────────┐
                                                                                 ▼
                                          8 값 후보·미리보기 ─▶ 9 화면 ─▶ 10 화면(묶기·결과)·문서
```

1~3 은 자료를 안 건드리는 순수 계산이라 단위 시험으로 끝난다. 4 부터 Testcontainers 가 필요하다.

---

### Task 1: 칸 카탈로그와 타입

무엇을 걸 수 있는지를 한 곳에 둔다. **카탈로그에 없는 키는 SQL 이 되지 않는다** — 인젝션 방어의 첫 겹이자, 원천 구성이 바뀌어 칸이 사라졌을 때 화면이 그것을 말할 수 있게 하는 장치다.

**Files:**
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FieldType.java`
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterableField.java`
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FieldCatalog.java`
- Test: `palim-reconcile/src/test/java/kr/suhsaechan/palim/reconcile/filter/FieldCatalogTest.java`

**Interfaces:**
- Consumes: 없음 (첫 과제)
- Produces:
  - `enum FieldType { TEXT, NUMBER, DATE, BOOL }`
  - `record FilterableField(String key, String label, FieldType type, String sqlExpression, boolean fromAttributes)` + `String sqlWith(String alias)`
  - `static Optional<FilterableField> FieldCatalog.find(String key)`
  - `static List<FilterableField> FieldCatalog.standard()`
  - `static List<FilterableField> FieldCatalog.attributeFields(List<String> keys)`
  - `static final String FieldCatalog.ATTRIBUTE_PREFIX = "attributes."`

- [ ] **Step 1: 실패하는 시험을 쓴다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 걸 수 있는 칸의 목록.
 *
 * <p>카탈로그를 두는 이유는 «사용자가 칸 이름을 정하지 않게» 하기 위해서다. 자유 입력이면
 * 오타가 저장되고 대조가 돌 때 터진다 — 매일 아침 스스로 도는 일에서 그것은 「어제까지 되던
 * 것이 오늘 죽었다」 로만 드러난다.
 */
class FieldCatalogTest {

    @Test
    @DisplayName("표준 칸은 이름으로 찾을 수 있고 SQL 표현식을 안다")
    void findsStandardField() {
        FilterableField field = FieldCatalog.find("warehouse_code").orElseThrow();

        assertThat(field.label()).isEqualTo("창고");
        assertThat(field.type()).isEqualTo(FieldType.TEXT);
        assertThat(field.sqlWith("s")).isEqualTo("s.warehouse_code");
        assertThat(field.fromAttributes()).isFalse();
    }

    @Test
    @DisplayName("유통기한은 날짜 칸이다 — 날짜 연산자만 쓸 수 있어야 한다")
    void expiryDateIsDate() {
        assertThat(FieldCatalog.find("expiry_date").orElseThrow().type())
                .isEqualTo(FieldType.DATE);
    }

    @Test
    @DisplayName("attributes 안의 원천 고유 칸도 걸 수 있다")
    void findsAttributeField() {
        FilterableField field = FieldCatalog.find("attributes.재고구분").orElseThrow();

        assertThat(field.type()).isEqualTo(FieldType.TEXT);
        assertThat(field.sqlWith("s")).isEqualTo("s.attributes->>'재고구분'");
        assertThat(field.fromAttributes()).isTrue();
    }

    @Test
    @DisplayName("카탈로그에 없는 이름은 찾지 못한다 — 이것이 인젝션 방어의 첫 겹이다")
    void rejectsUnknownField() {
        assertThat(FieldCatalog.find("tenant_id")).isEmpty();
        assertThat(FieldCatalog.find("id")).isEmpty();
        assertThat(FieldCatalog.find("1=1")).isEmpty();
        assertThat(FieldCatalog.find("")).isEmpty();
        assertThat(FieldCatalog.find(null)).isEmpty();
    }

    @Test
    @DisplayName("attributes 키에 따옴표가 섞이면 거부한다 — 표현식에 그대로 들어가는 자리다")
    void rejectsQuoteInAttributeKey() {
        assertThat(FieldCatalog.find("attributes.a'b")).isEmpty();
        assertThat(FieldCatalog.find("attributes.a\"b")).isEmpty();
        assertThat(FieldCatalog.find("attributes.a\\b")).isEmpty();
        assertThat(FieldCatalog.find("attributes.")).isEmpty();
    }
}
```

- [ ] **Step 2: 시험을 돌려 실패를 확인한다**

Run: `./gradlew :palim-reconcile:test --tests "*FieldCatalogTest*"`
Expected: 컴파일 실패 — `FieldCatalog` 를 찾을 수 없음

- [ ] **Step 3: `FieldType` 을 만든다**

```java
package kr.suhsaechan.palim.reconcile.filter;

/**
 * 걸 수 있는 칸의 값 종류.
 *
 * <p><b>연산자를 칸마다 고르지 않고 타입이 정한다.</b> 칸마다 고르면 그 판단이 칸 수만큼
 * 늘어나고, 늘어난 만큼 「왜 이 칸엔 이게 없지」 가 생긴다. 타입은 넷뿐이라 빠짐없이 채울 수 있다.
 *
 * <p>새 칸을 붙이는 데는 코드가 필요 없다 — 칸은 담긴 자료에서 나온다. 코드가 필요한 것은
 * <b>새 타입</b>뿐이고, 그것이 이 설계의 확장 지점이 칸에 있다는 뜻이다.
 */
public enum FieldType {
    TEXT,
    NUMBER,
    DATE,
    BOOL
}
```

- [ ] **Step 4: `FilterableField` 를 만든다**

```java
package kr.suhsaechan.palim.reconcile.filter;

/**
 * 조건을 걸 수 있는 칸 하나.
 *
 * @param key            저장되는 이름. {@code warehouse_code} 또는 {@code attributes.«키»}
 * @param label          화면에 보여줄 말. 이 화면을 쓰는 사람은 개발자가 아니다
 * @param type           값 종류. 쓸 수 있는 연산자와 화면 위젯이 이것으로 갈린다
 * @param sqlExpression  별칭 없는 SQL 표현식. 별칭은 조립하는 쪽이 붙인다
 * @param fromAttributes 표준 칸이 아니라 원천 고유 칸인가. 화면이 구분해 보여준다
 */
public record FilterableField(String key, String label, FieldType type,
                              String sqlExpression, boolean fromAttributes) {

    /** 별칭을 붙인 표현식. {@code s.warehouse_code} · {@code s.attributes->>'재고구분'} */
    public String sqlWith(String alias) {
        return "%s.%s".formatted(alias, sqlExpression);
    }
}
```

- [ ] **Step 5: `FieldCatalog` 를 만든다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 걸 수 있는 칸의 <b>전부</b>.
 *
 * <p>여기 없는 이름은 SQL 이 되지 않는다. 사용자가 칸 이름을 자유 입력하지 않게 하는 이유는
 * 셋이다 — 오타가 도는 순간까지 안 잡히고, 원천 구성이 바뀌어 칸이 사라지면 조건도 조용히
 * 사라지며, 식별자 자리에 임의 문자열이 들어갈 길을 애초에 없애기 위해서다.
 *
 * <p><b>표준에 없는 원천 칸도 전부 걸 수 있다.</b> 매핑되지 않은 원천 컬럼을 {@code attributes}
 * jsonb 에 통째로 살려 두기 때문이다. 그래서 원천 계정이 바뀌어 칸 구성이 달라져도 화면이
 * 그대로 동작한다 — 코드에 칸 이름을 박지 않는 이유가 이것이다.
 */
public final class FieldCatalog {

    /** {@code attributes} 안의 칸을 가리키는 접두어. */
    public static final String ATTRIBUTE_PREFIX = "attributes.";

    private static final Map<String, FilterableField> STANDARD = new LinkedHashMap<>();

    static {
        text("warehouse_code", "창고");
        text("warehouse_name", "창고명");
        text("lot_code", "로트");
        text("location_code", "로케이션");
        text("zone_code", "구역");
        text("quality_status", "품질상태");
        text("unit", "단위");
        text("base_unit", "기준단위");
        text("serial_no", "일련번호");
        text("raw_item_name", "원본 품명");
        text("normalized_name", "다듬은 품명");
        text("item_ref", "품목코드");
        text("currency", "통화");
        date("expiry_date", "유통기한");
        date("manufacture_date", "제조일");
        number("quantity", "원본 수량");
        number("base_quantity", "기준 수량");
        number("available_quantity", "가용 수량");
        number("reserved_quantity", "할당 수량");
        number("defective_quantity", "불량 수량");
        number("incoming_quantity", "입고 예정");
        number("outgoing_quantity", "출고 예정");
        number("unit_cost", "단가");
        number("amount", "금액");
    }

    private FieldCatalog() {
    }

    private static void text(String key, String label) {
        put(key, label, FieldType.TEXT);
    }

    private static void number(String key, String label) {
        put(key, label, FieldType.NUMBER);
    }

    private static void date(String key, String label) {
        put(key, label, FieldType.DATE);
    }

    private static void put(String key, String label, FieldType type) {
        STANDARD.put(key, new FilterableField(key, label, type, key, false));
    }

    /** 표준 칸 전부. 화면이 드롭다운을 그리는 데 쓴다. */
    public static List<FilterableField> standard() {
        return List.copyOf(STANDARD.values());
    }

    /**
     * 이름으로 칸을 찾는다. <b>못 찾으면 비어 있다</b> — 부르는 쪽이 거부해야 한다.
     *
     * <p>{@code attributes.«키»} 는 목록에 없어도 만들어 준다. 원천마다 키가 다르고 그 목록은
     * 담긴 자료에서만 알 수 있기 때문이다. 대신 <b>따옴표·역슬래시가 섞인 키는 거부한다</b> —
     * 그 값은 표현식 문자열에 그대로 들어가는 유일한 자리다.
     */
    public static Optional<FilterableField> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        FilterableField standard = STANDARD.get(key);
        if (standard != null) {
            return Optional.of(standard);
        }
        if (!key.startsWith(ATTRIBUTE_PREFIX)) {
            return Optional.empty();
        }
        String attribute = key.substring(ATTRIBUTE_PREFIX.length());
        if (attribute.isBlank()
                || attribute.indexOf('\'') >= 0
                || attribute.indexOf('"') >= 0
                || attribute.indexOf('\\') >= 0) {
            return Optional.empty();
        }
        // 원천 고유 칸은 언제나 글로 읽는다. jsonb 에서 ->> 로 꺼내면 문자열이기 때문이고,
        // 숫자로 다루고 싶으면 매핑에서 표준 칸으로 옮기는 것이 옳은 자리다.
        return Optional.of(new FilterableField(key, attribute, FieldType.TEXT,
                "attributes->>'%s'".formatted(attribute), true));
    }

    /** 담긴 자료에서 찾은 원천 고유 키들을 걸 수 있는 칸으로 바꾼다. */
    public static List<FilterableField> attributeFields(List<String> keys) {
        return keys.stream()
                .map(key -> find(ATTRIBUTE_PREFIX + key))
                .flatMap(Optional::stream)
                .toList();
    }
}
```

- [ ] **Step 6: 시험을 돌려 통과를 확인한다**

Run: `./gradlew :palim-reconcile:test --tests "*FieldCatalogTest*"`
Expected: PASS (5건)

- [ ] **Step 7: 커밋**

```bash
git add palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/ \
        palim-reconcile/src/test/java/kr/suhsaechan/palim/reconcile/filter/
git commit -m "창고 말고 다른 조건으로는 대조를 좁힐 수가 없다 : feat : 걸 수 있는 칸을 카탈로그로 두어 그 밖의 이름은 SQL 이 되지 않게 함 https://github.com/Cassiiopeia/palim/issues/161"
```

---

### Task 2: 연산자와 날짜 토큰

연산자를 **타입별로 빠짐없이** 채운다. 그리고 날짜는 고정값이 아니라 **상대값**으로 받는다 — 대조는 매일 아침 스스로 도는데 고정 날짜를 박으면 그날만 맞고 다음 날부터 조용히 어긋난다.

이 과제는 SQL 을 만들지 않는다. 값 개수 규칙과 허용 타입만 정한다 — SQL 은 Task 3 의 AST 가 만든다.

**Files:**
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterOperator.java`
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/DateToken.java`
- Test: `palim-reconcile/src/test/java/kr/suhsaechan/palim/reconcile/filter/FilterOperatorTest.java`
- Test: `palim-reconcile/src/test/java/kr/suhsaechan/palim/reconcile/filter/DateTokenTest.java`

**Interfaces:**
- Consumes: `FieldType` (Task 1)
- Produces:
  - `enum FilterOperator` — 상수: `IN NOT_IN EQ NE GT GTE LT LTE BETWEEN NOT_BETWEEN CONTAINS NOT_CONTAINS STARTS_WITH ENDS_WITH MATCHES IS_EMPTY IS_NOT_EMPTY IS_TRUE IS_FALSE`
  - `boolean FilterOperator.supports(FieldType type)`
  - `boolean FilterOperator.acceptsCount(int count)`
  - `String FilterOperator.label()` — 화면에 보여줄 말
  - `String FilterOperator.symbol()` — 식에 쓰는 글자
  - `static List<FilterOperator> FilterOperator.forType(FieldType type)`
  - `static Optional<FilterOperator> FilterOperator.ofSymbol(String token)`
  - `record DateToken(String raw)` + `LocalDate resolve(Instant asOf)` + `static Optional<DateToken> parse(String raw)`

- [ ] **Step 1: 연산자 시험을 쓴다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 연산자는 타입이 정한다.
 *
 * <p>부정형을 짝으로 두는 이유가 중요하다. {@code IN} 만 있고 {@code NOT_IN} 이 없으면
 * 「불량만 빼고 전부」 를 하려고 나머지 값을 전부 체크해야 하는데, <b>값이 늘어나는 날 조건이
 * 조용히 낡는다</b> — 새로 생긴 값이 빠진 채로 돈다.
 */
class FilterOperatorTest {

    @Test
    @DisplayName("글 칸에는 담기·포함·비었음이 있고 크기 비교는 없다")
    void textOperators() {
        assertThat(FilterOperator.forType(FieldType.TEXT))
                .contains(FilterOperator.IN, FilterOperator.NOT_IN,
                        FilterOperator.CONTAINS, FilterOperator.NOT_CONTAINS,
                        FilterOperator.STARTS_WITH, FilterOperator.ENDS_WITH,
                        FilterOperator.MATCHES,
                        FilterOperator.IS_EMPTY, FilterOperator.IS_NOT_EMPTY)
                .doesNotContain(FilterOperator.GT, FilterOperator.BETWEEN);
    }

    @Test
    @DisplayName("날짜·숫자 칸에는 크기 비교와 사이가 있다")
    void comparableOperators() {
        assertThat(FilterOperator.forType(FieldType.DATE))
                .contains(FilterOperator.GTE, FilterOperator.LTE,
                        FilterOperator.BETWEEN, FilterOperator.NOT_BETWEEN)
                .doesNotContain(FilterOperator.CONTAINS);
        assertThat(FilterOperator.forType(FieldType.NUMBER))
                .contains(FilterOperator.GT, FilterOperator.NE);
    }

    @Test
    @DisplayName("모든 부정형에 짝이 있다 — 하나라도 빠지면 값이 늘 때 조건이 낡는다")
    void negationsArePaired() {
        for (FieldType type : FieldType.values()) {
            var ops = FilterOperator.forType(type);
            if (ops.contains(FilterOperator.IN)) {
                assertThat(ops).contains(FilterOperator.NOT_IN);
            }
            if (ops.contains(FilterOperator.CONTAINS)) {
                assertThat(ops).contains(FilterOperator.NOT_CONTAINS);
            }
            if (ops.contains(FilterOperator.BETWEEN)) {
                assertThat(ops).contains(FilterOperator.NOT_BETWEEN);
            }
            if (ops.contains(FilterOperator.IS_EMPTY)) {
                assertThat(ops).contains(FilterOperator.IS_NOT_EMPTY);
            }
        }
    }

    @Test
    @DisplayName("값 개수는 연산자가 정한다 — 화면과 서버가 같은 규칙을 한 곳에서 읽는다")
    void valueCounts() {
        assertThat(FilterOperator.IS_EMPTY.acceptsCount(0)).isTrue();
        assertThat(FilterOperator.IS_EMPTY.acceptsCount(1)).isFalse();

        assertThat(FilterOperator.EQ.acceptsCount(1)).isTrue();
        assertThat(FilterOperator.EQ.acceptsCount(0)).isFalse();
        assertThat(FilterOperator.EQ.acceptsCount(2)).isFalse();

        assertThat(FilterOperator.BETWEEN.acceptsCount(2)).isTrue();
        assertThat(FilterOperator.BETWEEN.acceptsCount(1)).isFalse();

        assertThat(FilterOperator.IN.acceptsCount(1)).isTrue();
        assertThat(FilterOperator.IN.acceptsCount(5)).isTrue();
        // 값이 0개인 IN 은 SQL 에서 IN () 이 되어 문법 오류다. 저장에서 막는다.
        assertThat(FilterOperator.IN.acceptsCount(0)).isFalse();
    }

    @Test
    @DisplayName("식에 쓰는 글자로 연산자를 찾는다 — 한글과 기호를 함께 받는다")
    void findsBySymbol() {
        assertThat(FilterOperator.ofSymbol("=")).contains(FilterOperator.EQ);
        assertThat(FilterOperator.ofSymbol("!=")).contains(FilterOperator.NE);
        assertThat(FilterOperator.ofSymbol("≠")).contains(FilterOperator.NE);
        assertThat(FilterOperator.ofSymbol(">=")).contains(FilterOperator.GTE);
        assertThat(FilterOperator.ofSymbol("포함")).contains(FilterOperator.CONTAINS);
        assertThat(FilterOperator.ofSymbol("CONTAINS")).contains(FilterOperator.CONTAINS);
        assertThat(FilterOperator.ofSymbol("contains")).contains(FilterOperator.CONTAINS);
        assertThat(FilterOperator.ofSymbol("DROP")).isEmpty();
    }

    @Test
    @DisplayName("불리언 칸에는 참·거짓·비었음·값있음만 있다")
    void boolOperators() {
        assertThat(FilterOperator.forType(FieldType.BOOL))
                .containsExactlyInAnyOrder(FilterOperator.IS_TRUE, FilterOperator.IS_FALSE,
                        FilterOperator.IS_EMPTY, FilterOperator.IS_NOT_EMPTY);
    }
}
```

- [ ] **Step 2: 날짜 토큰 시험을 쓴다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 날짜는 고정값이면 안 된다.
 *
 * <p>대조는 <b>매일 아침 스스로 돈다.</b> 「유통기한 2026-08-22 이후」 를 박으면 그날만 맞고
 * 다음 날부터 조용히 어긋난다 — 그리고 그것은 「어제는 됐는데 오늘은 안 된다」 로만 드러나
 * 원인을 찾기 어렵다.
 */
class DateTokenTest {

    /** 2026-08-22 09:00 KST = 2026-08-22 00:00Z. 업무 시간대가 Asia/Seoul 임을 시험한다. */
    private static final Instant NOON_KST = Instant.parse("2026-08-22T03:00:00Z");

    @Test
    @DisplayName("「오늘」 은 회차가 도는 날로 풀린다")
    void resolvesToday() {
        assertThat(DateToken.parse("오늘").orElseThrow().resolve(NOON_KST))
                .isEqualTo(LocalDate.of(2026, 8, 22));
        assertThat(DateToken.parse("TODAY").orElseThrow().resolve(NOON_KST))
                .isEqualTo(LocalDate.of(2026, 8, 22));
    }

    @Test
    @DisplayName("「오늘+30」 · 「오늘-7」 로 앞뒤를 잡는다")
    void resolvesOffsets() {
        assertThat(DateToken.parse("오늘+30").orElseThrow().resolve(NOON_KST))
                .isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(DateToken.parse("TODAY-7").orElseThrow().resolve(NOON_KST))
                .isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    @DisplayName("고정 날짜도 그대로 받는다 — 특정 시점을 못 박아야 할 때가 있다")
    void resolvesFixedDate() {
        assertThat(DateToken.parse("2026-01-31").orElseThrow().resolve(NOON_KST))
                .isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    @DisplayName("업무 시간대는 Asia/Seoul 이다 — UTC 로 보면 하루가 어긋난다")
    void usesBusinessZone() {
        // 2026-08-21 15:30Z = 2026-08-22 00:30 KST. 한국에서는 이미 22일이다.
        Instant lateNight = Instant.parse("2026-08-21T15:30:00Z");
        assertThat(DateToken.parse("오늘").orElseThrow().resolve(lateNight))
                .isEqualTo(LocalDate.of(2026, 8, 22));
    }

    @Test
    @DisplayName("읽을 수 없는 값은 비어 있다 — 저장에서 막힌다")
    void rejectsGarbage() {
        assertThat(DateToken.parse("어제")).isEmpty();
        assertThat(DateToken.parse("오늘+")).isEmpty();
        assertThat(DateToken.parse("오늘+하루")).isEmpty();
        assertThat(DateToken.parse("2026-13-01")).isEmpty();
        assertThat(DateToken.parse("")).isEmpty();
        assertThat(DateToken.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("터무니없이 먼 상대값은 거부한다 — 오타를 조건으로 받아 두면 결과가 통째로 빈다")
    void rejectsAbsurdOffset() {
        assertThat(DateToken.parse("오늘+100000")).isEmpty();
        assertThat(DateToken.parse("오늘-100000")).isEmpty();
    }

    @Test
    @DisplayName("원래 표현을 그대로 들고 있다 — 회차에 「무슨 규칙이었나」 를 남겨야 한다")
    void keepsRawForm() {
        assertThat(DateToken.parse("오늘+30").orElseThrow().raw()).isEqualTo("오늘+30");
    }
}
```

- [ ] **Step 3: 두 시험을 돌려 실패를 확인한다**

Run: `./gradlew :palim-reconcile:test --tests "*FilterOperatorTest*" --tests "*DateTokenTest*"`
Expected: 컴파일 실패 — `FilterOperator`·`DateToken` 없음

- [ ] **Step 4: `FilterOperator` 를 만든다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 조건 한 줄의 <b>문법</b>.
 *
 * <p>칸 이름은 담긴 자료에서 뽑는데 연산자는 코드에 박는다. 비대칭이지만 이유가 있다 —
 * 연산자 하나마다 SQL 틀·값 개수 규칙·화면 위젯·이름 넷이 붙고, 그것은 전부 코드다. 표로 빼도
 * 셋은 여전히 코드라 「확장 가능한 척」 만 된다.
 *
 * <p>그래서 확장 지점을 칸에 둔다. 칸은 자료에서 나오니 무한하고, 연산자는 그 칸을 다루는
 * 문법이라 유한하다. <b>유한하다면 타입별로 빠짐없어야</b> 「이 연산자가 없어서 못 한다」 가
 * 나오지 않는다.
 *
 * <p><b>부정형을 짝으로 둔다.</b> {@code IN} 만 있고 {@code NOT_IN} 이 없으면 「불량만 빼고
 * 전부」 를 하려고 나머지를 전부 체크해야 하는데, 값이 늘어나는 날 새 값이 빠진 채로 돈다.
 * 부정형은 편의가 아니라 <b>자료가 늘어도 안 낡는 조건</b>을 쓸 수 있게 하는 것이다.
 */
public enum FilterOperator {

    IN("이것만", "IN", Arity.AT_LEAST_ONE, EnumSet.of(FieldType.TEXT, FieldType.NUMBER)),
    NOT_IN("이것 빼고", "NOT IN", Arity.AT_LEAST_ONE,
            EnumSet.of(FieldType.TEXT, FieldType.NUMBER)),

    EQ("같음", "=", Arity.ONE,
            EnumSet.of(FieldType.TEXT, FieldType.NUMBER, FieldType.DATE)),
    NE("다름", "≠", Arity.ONE,
            EnumSet.of(FieldType.TEXT, FieldType.NUMBER, FieldType.DATE)),

    GT("초과", ">", Arity.ONE, EnumSet.of(FieldType.NUMBER, FieldType.DATE)),
    GTE("이후", ">=", Arity.ONE, EnumSet.of(FieldType.NUMBER, FieldType.DATE)),
    LT("미만", "<", Arity.ONE, EnumSet.of(FieldType.NUMBER, FieldType.DATE)),
    LTE("이전", "<=", Arity.ONE, EnumSet.of(FieldType.NUMBER, FieldType.DATE)),

    BETWEEN("사이", "BETWEEN", Arity.TWO, EnumSet.of(FieldType.NUMBER, FieldType.DATE)),
    NOT_BETWEEN("사이 빼고", "NOT BETWEEN", Arity.TWO,
            EnumSet.of(FieldType.NUMBER, FieldType.DATE)),

    CONTAINS("포함", "포함", Arity.ONE, EnumSet.of(FieldType.TEXT)),
    NOT_CONTAINS("포함 안 함", "포함안함", Arity.ONE, EnumSet.of(FieldType.TEXT)),
    STARTS_WITH("이렇게 시작", "시작", Arity.ONE, EnumSet.of(FieldType.TEXT)),
    ENDS_WITH("이렇게 끝", "끝", Arity.ONE, EnumSet.of(FieldType.TEXT)),
    /** 정규식. {@code RegexGuard} 로 폭주하는 패턴을 막는다 — 정규화 규칙에서 쓰던 장치다. */
    MATCHES("규칙에 맞음", "MATCHES", Arity.ONE, EnumSet.of(FieldType.TEXT)),

    IS_EMPTY("비었음", "비었음", Arity.NONE,
            EnumSet.of(FieldType.TEXT, FieldType.NUMBER, FieldType.DATE, FieldType.BOOL)),
    IS_NOT_EMPTY("값 있음", "값있음", Arity.NONE,
            EnumSet.of(FieldType.TEXT, FieldType.NUMBER, FieldType.DATE, FieldType.BOOL)),

    IS_TRUE("참", "참", Arity.NONE, EnumSet.of(FieldType.BOOL)),
    IS_FALSE("거짓", "거짓", Arity.NONE, EnumSet.of(FieldType.BOOL));

    /** 값이 몇 개 필요한가. 화면과 서버가 <b>같은 규칙을 한 곳에서</b> 읽는다. */
    public enum Arity {
        NONE, ONE, TWO, AT_LEAST_ONE
    }

    private final String label;
    private final String symbol;
    private final Arity arity;
    private final Set<FieldType> types;

    FilterOperator(String label, String symbol, Arity arity, Set<FieldType> types) {
        this.label = label;
        this.symbol = symbol;
        this.arity = arity;
        this.types = types;
    }

    public String label() {
        return label;
    }

    /** 식에 쓰는 글자. */
    public String symbol() {
        return symbol;
    }

    public Arity arity() {
        return arity;
    }

    public boolean supports(FieldType type) {
        return types.contains(type);
    }

    /**
     * 이 개수의 값을 받을 수 있는가.
     *
     * <p>값이 0개인 {@code IN} 은 SQL 에서 {@code IN ()} 이 되어 문법 오류다. 「전부」 는 조건을
     * 두지 않는 것으로 표현하지, 빈 목록으로 표현하지 않는다.
     */
    public boolean acceptsCount(int count) {
        return switch (arity) {
            case NONE -> count == 0;
            case ONE -> count == 1;
            case TWO -> count == 2;
            case AT_LEAST_ONE -> count >= 1;
        };
    }

    /** 그 타입에 쓸 수 있는 연산자. 화면이 드롭다운을 그리는 데 쓴다. */
    public static List<FilterOperator> forType(FieldType type) {
        return Arrays.stream(values()).filter(op -> op.supports(type)).toList();
    }

    /**
     * 식에서 읽은 글자로 연산자를 찾는다.
     *
     * <p>한글과 기호를 함께 받는 이유는, 이 화면을 쓰는 사람이 개발자가 아니기 때문이다.
     * 「포함」 으로 쓰든 {@code CONTAINS} 로 쓰든 같은 연산자가 된다.
     */
    public static Optional<FilterOperator> ofSymbol(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String normalized = token.trim().toUpperCase(Locale.ROOT);
        String noSpace = normalized.replace(" ", "");
        for (FilterOperator op : values()) {
            if (op.symbol.toUpperCase(Locale.ROOT).replace(" ", "").equals(noSpace)
                    || op.name().equals(noSpace)) {
                return Optional.of(op);
            }
        }
        // 흔한 다른 표기. 늘리기 쉬우라고 여기 모아 둔다.
        return switch (noSpace) {
            case "!=", "<>" -> Optional.of(NE);
            case "==" -> Optional.of(EQ);
            case "≥" -> Optional.of(GTE);
            case "≤" -> Optional.of(LTE);
            default -> Optional.empty();
        };
    }
}
```

- [ ] **Step 5: `DateToken` 을 만든다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 날짜 조건의 값. <b>고정값과 상대값을 함께 받는다.</b>
 *
 * <p>대조는 매일 아침 스스로 돈다. 「유통기한 2026-08-22 이후」 를 박으면 그날만 맞고 다음
 * 날부터 조용히 어긋난다 — 그리고 그것은 「어제는 됐는데 오늘은 안 된다」 로만 드러나 원인을
 * 찾기 어렵다.
 *
 * <p><b>푸는 시점이 저장이 아니라 실행이다.</b> 저장할 때 풀면 저장한 날짜로 굳어 같은 문제가
 * 된다. 그래서 원래 표현을 그대로 들고 있다가 회차가 돌 때 그 회차의 기준 시각으로 푼다.
 *
 * @param raw 사람이 적은 그대로. 회차에 「무슨 규칙이었나」 를 남기려면 이것이 필요하다
 */
public record DateToken(String raw) {

    /** 업무 시간대. {@code BaseAtGranularity} 와 같은 값이다 — 한쪽만 바뀌면 하루가 어긋난다. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    /** 상대값의 최대 폭. 오타(자릿수 하나 더)를 조건으로 받아 두면 결과가 통째로 빈다. */
    private static final int MAX_OFFSET_DAYS = 36_500;

    private static final String TODAY_KO = "오늘";
    private static final String TODAY_EN = "TODAY";

    /** 읽을 수 없으면 비어 있다 — 저장에서 막고, 어디가 문제인지 화면이 가리킨다. */
    public static Optional<DateToken> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        return looksRelative(value) || isFixed(value)
                ? Optional.of(new DateToken(value))
                : Optional.empty();
    }

    /** 이 회차의 기준 시각으로 푼다. */
    public LocalDate resolve(Instant asOf) {
        LocalDate today = asOf.atZone(BUSINESS_ZONE).toLocalDate();
        String head = head(raw);
        if (head == null) {
            return LocalDate.parse(raw);
        }
        String rest = raw.substring(head.length());
        if (rest.isEmpty()) {
            return today;
        }
        return today.plusDays(Long.parseLong(rest.charAt(0) == '+' ? rest.substring(1) : rest));
    }

    /** 상대값인가. 화면이 날짜칸 대신 「오늘 ▾」 위젯을 그리는 데 쓴다. */
    public boolean isRelative() {
        return head(raw) != null;
    }

    /** 「오늘」 을 뺀 나머지가 붙는 날수. 상대값이 아니면 0. */
    public long offsetDays() {
        String head = head(raw);
        if (head == null) {
            return 0;
        }
        String rest = raw.substring(head.length());
        if (rest.isEmpty()) {
            return 0;
        }
        return Long.parseLong(rest.charAt(0) == '+' ? rest.substring(1) : rest);
    }

    private static String head(String value) {
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        if (upper.startsWith(TODAY_EN)) {
            return value.substring(0, TODAY_EN.length());
        }
        if (value.startsWith(TODAY_KO)) {
            return TODAY_KO;
        }
        return null;
    }

    private static boolean looksRelative(String value) {
        String head = head(value);
        if (head == null) {
            return false;
        }
        String rest = value.substring(head.length());
        if (rest.isEmpty()) {
            return true;
        }
        if (rest.charAt(0) != '+' && rest.charAt(0) != '-') {
            return false;
        }
        String digits = rest.substring(1);
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            return Long.parseLong(digits) <= MAX_OFFSET_DAYS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isFixed(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
```

- [ ] **Step 6: 시험을 돌려 통과를 확인한다**

Run: `./gradlew :palim-reconcile:test --tests "*FilterOperatorTest*" --tests "*DateTokenTest*"`
Expected: PASS (13건)

> `resolve` 의 음수 처리를 확인한다. `오늘-7` 은 `rest = "-7"` 이라 `charAt(0) == '-'` 이므로 `Long.parseLong("-7")` 이 되어 `plusDays(-7)` 이다. `오늘+30` 은 `+` 를 떼고 `30` 이 된다.

- [ ] **Step 7: 커밋**

```bash
git add palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterOperator.java \
        palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/DateToken.java \
        palim-reconcile/src/test/java/kr/suhsaechan/palim/reconcile/filter/FilterOperatorTest.java \
        palim-reconcile/src/test/java/kr/suhsaechan/palim/reconcile/filter/DateTokenTest.java
git commit -m "창고 말고 다른 조건으로는 대조를 좁힐 수가 없다 : feat : 연산자를 타입별로 빠짐없이 두고 날짜를 상대값으로 받아 매일 도는 조건이 낡지 않게 함 https://github.com/Cassiiopeia/palim/issues/161"
```

---

### Task 3: AST 와 SQL 생성

**이 과제가 안전의 핵심이다.** 사용자 글은 AST 가 되고, SQL 은 AST 노드가 적어 둔 틀에서만 나온다. 사용자 문자열이 SQL 에 이어붙는 자리가 하나도 없다 — 식별자는 카탈로그에서, 값은 전부 바인딩 파라미터로 간다.

**Files:**
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterSql.java`
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterNode.java`
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterSpec.java`
- Test: `palim-reconcile/src/test/java/kr/suhsaechan/palim/reconcile/filter/FilterSpecTest.java`

**Interfaces:**
- Consumes: `FilterableField`·`FieldType` (Task 1), `FilterOperator`·`DateToken` (Task 2)
- Produces:
  - `final class FilterSql` — `String bind(Object value)`, `void append(String sql)`, `String sql()`, `Map<String,Object> params()`, `Instant asOf()`, `String alias()`
  - `sealed interface FilterNode permits FilterNode.All, FilterNode.And, FilterNode.Or, FilterNode.Not, FilterNode.Compare`
    - `void appendTo(FilterSql out)`
    - `int nodeCount()` · `int depth()`
    - `static FilterNode.All ALL` (상수 `FilterNode.ALL`)
    - `record And(List<FilterNode> children)` · `record Or(List<FilterNode> children)` · `record Not(FilterNode child)`
    - `record Compare(FilterableField field, FilterOperator operator, List<String> values)`
  - `record FilterSpec(FilterNode root)`
    - `static FilterSpec all()`
    - `boolean isAll()`
    - `String sqlAnd(String alias, String prefix, Instant asOf)`
    - `Map<String,Object> params(String prefix, Instant asOf)`
    - `record Compiled(String sql, Map<String,Object> params)` · `Compiled compile(String alias, String prefix, Instant asOf)`
    - `String describe()`

- [ ] **Step 1: 실패하는 시험을 쓴다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 조건을 SQL 로 만든다.
 *
 * <p><b>사용자 문자열이 SQL 에 이어붙는 자리가 없다.</b> 식별자는 카탈로그에서만 나오고 값은
 * 전부 바인딩 파라미터로 간다. 이 시험이 지키는 것이 그 성질이다.
 */
class FilterSpecTest {

    private static final Instant AS_OF = Instant.parse("2026-08-22T03:00:00Z");

    private static FilterNode.Compare compare(String key, FilterOperator op, String... values) {
        return new FilterNode.Compare(FieldCatalog.find(key).orElseThrow(), op, List.of(values));
    }

    @Test
    @DisplayName("조건이 없으면 빈 문자열이다 — IN () 은 문법 오류라 「전부」 를 빈 목록으로 쓸 수 없다")
    void allProducesNothing() {
        FilterSpec spec = FilterSpec.all();

        assertThat(spec.isAll()).isTrue();
        assertThat(spec.sqlAnd("s", "f", AS_OF)).isEmpty();
        assertThat(spec.params("f", AS_OF)).isEmpty();
    }

    @Test
    @DisplayName("값은 언제나 바인딩으로 간다 — SQL 문자열에 값이 나타나지 않는다")
    void bindsValues() {
        FilterSpec spec = new FilterSpec(compare("warehouse_code", FilterOperator.IN,
                "01", "02"));

        FilterSpec.Compiled compiled = spec.compile("s", "f", AS_OF);

        assertThat(compiled.sql()).isEqualTo(" AND (s.warehouse_code IN (:f0, :f1))");
        assertThat(compiled.params()).containsExactly(
                org.assertj.core.api.Assertions.entry("f0", "01"),
                org.assertj.core.api.Assertions.entry("f1", "02"));
        assertThat(compiled.sql()).doesNotContain("01").doesNotContain("02");
    }

    @Test
    @DisplayName("주입을 노린 값도 값일 뿐이다 — 바인딩되므로 SQL 이 되지 않는다")
    void injectionPayloadStaysAValue() {
        String payload = "01'); DROP TABLE std_stock_snapshot; --";
        FilterSpec spec = new FilterSpec(compare("warehouse_code", FilterOperator.EQ, payload));

        FilterSpec.Compiled compiled = spec.compile("s", "f", AS_OF);

        assertThat(compiled.sql()).isEqualTo(" AND (s.warehouse_code = :f0)");
        assertThat(compiled.sql()).doesNotContain("DROP").doesNotContain("--");
        assertThat(compiled.params()).containsEntry("f0", payload);
    }

    @Test
    @DisplayName("숫자 칸은 숫자로, 날짜 칸은 날짜로 바인딩한다")
    void bindsTypedValues() {
        FilterSpec numeric = new FilterSpec(
                compare("base_quantity", FilterOperator.GTE, "100"));
        assertThat(numeric.params("f", AS_OF)).containsEntry("f0", new BigDecimal("100"));

        FilterSpec dated = new FilterSpec(
                compare("expiry_date", FilterOperator.GTE, "오늘"));
        assertThat(dated.params("f", AS_OF))
                .containsEntry("f0", LocalDate.of(2026, 8, 22));
    }

    @Test
    @DisplayName("상대 날짜는 회차 기준 시각으로 풀린다 — 하루 뒤에 돌리면 범위도 하루 밀린다")
    void relativeDateFollowsRunTime() {
        FilterSpec spec = new FilterSpec(compare("expiry_date", FilterOperator.GTE, "오늘+30"));

        assertThat(spec.params("f", AS_OF))
                .containsEntry("f0", LocalDate.of(2026, 9, 21));
        assertThat(spec.params("f", AS_OF.plus(java.time.Duration.ofDays(1))))
                .containsEntry("f0", LocalDate.of(2026, 9, 22));
    }

    @Test
    @DisplayName("값이 없는 연산자는 바인딩이 없다")
    void noValueOperators() {
        FilterSpec spec = new FilterSpec(compare("lot_code", FilterOperator.IS_EMPTY));

        FilterSpec.Compiled compiled = spec.compile("s", "f", AS_OF);

        // lot_code 는 NOT NULL DEFAULT '' 다. 「비었음」 은 NULL 과 빈 문자열을 함께 본다.
        assertThat(compiled.sql())
                .isEqualTo(" AND (coalesce(s.lot_code, '') = '')");
        assertThat(compiled.params()).isEmpty();
    }

    @Test
    @DisplayName("AND · OR · 괄호가 중첩된다")
    void nestsBooleans() {
        FilterNode node = new FilterNode.And(List.of(
                new FilterNode.Or(List.of(
                        compare("warehouse_code", FilterOperator.EQ, "01"),
                        compare("warehouse_code", FilterOperator.EQ, "02"))),
                new FilterNode.Not(compare("quality_status", FilterOperator.EQ, "불량"))));

        FilterSpec.Compiled compiled = new FilterSpec(node).compile("s", "f", AS_OF);

        assertThat(compiled.sql()).isEqualTo(
                " AND ((s.warehouse_code = :f0 OR s.warehouse_code = :f1)"
                        + " AND NOT (s.quality_status = :f2))");
        assertThat(compiled.params()).hasSize(3);
    }

    @Test
    @DisplayName("접두어가 다르면 바인딩 이름이 겹치지 않는다 — 좌·우를 한 쿼리에 거는 자리가 있다")
    void prefixKeepsNamesApart() {
        FilterSpec left = new FilterSpec(compare("warehouse_code", FilterOperator.EQ, "01"));
        FilterSpec right = new FilterSpec(compare("warehouse_code", FilterOperator.EQ, "99"));

        assertThat(left.params("lf", AS_OF)).containsOnlyKeys("lf0");
        assertThat(right.params("rf", AS_OF)).containsOnlyKeys("rf0");
    }

    @Test
    @DisplayName("두 번 컴파일해도 같은 이름·같은 값이 나온다 — sqlAnd 와 params 를 따로 부른다")
    void compileIsDeterministic() {
        FilterSpec spec = new FilterSpec(new FilterNode.And(List.of(
                compare("warehouse_code", FilterOperator.IN, "01", "02"),
                compare("quality_status", FilterOperator.EQ, "정상"))));

        assertThat(spec.sqlAnd("s", "f", AS_OF)).isEqualTo(spec.sqlAnd("s", "f", AS_OF));
        assertThat(spec.params("f", AS_OF)).isEqualTo(spec.params("f", AS_OF));
        assertThat(spec.sqlAnd("s", "f", AS_OF)).contains(":f0", ":f1", ":f2");
    }

    @Test
    @DisplayName("포함은 LIKE 로 가되 값의 % 와 _ 를 글자로 다룬다")
    void containsEscapesWildcards() {
        FilterSpec spec = new FilterSpec(
                compare("raw_item_name", FilterOperator.CONTAINS, "50%_A"));

        FilterSpec.Compiled compiled = spec.compile("s", "f", AS_OF);

        assertThat(compiled.sql())
                .isEqualTo(" AND (s.raw_item_name LIKE :f0 ESCAPE '\\')");
        assertThat(compiled.params()).containsEntry("f0", "%50\\%\\_A%");
    }

    @Test
    @DisplayName("사이는 두 값을 쓴다")
    void betweenUsesTwoValues() {
        FilterSpec spec = new FilterSpec(
                compare("expiry_date", FilterOperator.BETWEEN, "오늘", "오늘+30"));

        assertThat(spec.sqlAnd("s", "f", AS_OF))
                .isEqualTo(" AND (s.expiry_date BETWEEN :f0 AND :f1)");
    }

    @Test
    @DisplayName("몇 개인지·얼마나 깊은지를 센다 — 폭주를 막는 쪽이 이 값을 본다")
    void countsSize() {
        FilterNode node = new FilterNode.And(List.of(
                compare("warehouse_code", FilterOperator.EQ, "01"),
                new FilterNode.Or(List.of(
                        compare("lot_code", FilterOperator.IS_EMPTY),
                        compare("zone_code", FilterOperator.IS_EMPTY)))));

        assertThat(node.nodeCount()).isEqualTo(5);
        assertThat(node.depth()).isEqualTo(3);
    }
}
```

- [ ] **Step 2: 시험을 돌려 실패를 확인한다**

Run: `./gradlew :palim-reconcile:test --tests "*FilterSpecTest*"`
Expected: 컴파일 실패 — `FilterSql`·`FilterNode`·`FilterSpec` 없음

- [ ] **Step 3: `FilterSql` 을 만든다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQL 조각과 바인딩 값을 <b>함께</b> 쌓는 그릇.
 *
 * <p>둘을 따로 만들면 언젠가 어긋난다 — 조각에는 있는데 값이 없거나 그 반대다. 그런 어긋남은
 * 실행 시점에 「파라미터가 없습니다」 로만 드러나 어느 조건 때문인지 알기 어렵다.
 *
 * <p><b>이름은 접두어 + 순번으로만 짓는다.</b> 한 쿼리가 좌·우 두 조건을 동시에 거는 자리가
 * 있다(품목 묶기). 이름이 겹치면 뒤에 넣은 값이 앞을 덮어써 <b>양쪽이 같은 조건으로 걸린다</b> —
 * 화면은 멀쩡해 보이는데 한쪽이 통째로 비거나 엉뚱한 줄이 짝으로 잡힌다. 순번으로 뽑으면 겹칠
 * 방법이 없다.
 */
public final class FilterSql {

    private final StringBuilder sql = new StringBuilder();
    private final Map<String, Object> params = new LinkedHashMap<>();
    private final String alias;
    private final String prefix;
    private final Instant asOf;
    private int seq;

    public FilterSql(String alias, String prefix, Instant asOf) {
        this.alias = alias;
        this.prefix = prefix;
        this.asOf = asOf;
    }

    public String alias() {
        return alias;
    }

    /** 이 회차의 기준 시각. 상대 날짜를 푸는 데 쓴다. */
    public Instant asOf() {
        return asOf;
    }

    public void append(String fragment) {
        sql.append(fragment);
    }

    /** 값을 담고 그 이름을 돌려준다. 값이 SQL 문자열로 가는 길은 이것뿐이다. */
    public String bind(Object value) {
        String name = prefix + seq++;
        params.put(name, value);
        return ":" + name;
    }

    public String sql() {
        return sql.toString();
    }

    public Map<String, Object> params() {
        return Map.copyOf(params);
    }
}
```

- [ ] **Step 4: `FilterNode` 를 만든다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 조건 하나를 나타내는 나무.
 *
 * <p>조건 줄과 식이 <b>둘 다 이것으로 모인다.</b> 두 입구가 각자 SQL 을 만들면 「줄로 건 것과
 * 식으로 건 것이 다르게 돈다」 가 언젠가 생기고, 그 차이는 숫자로만 드러나 원인을 찾기 어렵다.
 * 이 프로젝트는 판단이 두 군데로 갈려 어긋난 일을 이미 두 번 겪었다(07-DECISIONS 030·032).
 *
 * <p>그리고 경로가 하나면 <b>「지금 조건을 식으로 보기」 가 공짜로 나온다</b> — 나무를 글로
 * 되돌리면 된다.
 */
public sealed interface FilterNode {

    /** 아무것도 거르지 않음. */
    FilterNode ALL = new All();

    /** 자기 자신을 SQL 로 적는다. <b>여기 적힌 틀 밖의 SQL 은 만들어지지 않는다.</b> */
    void appendTo(FilterSql out);

    /** 노드 수. 폭주를 막는 쪽이 본다. */
    int nodeCount();

    /** 나무의 깊이. 마찬가지. */
    int depth();

    /** 이 나무가 아무것도 거르지 않는가. */
    default boolean isAll() {
        return this instanceof All;
    }

    record All() implements FilterNode {
        @Override
        public void appendTo(FilterSql out) {
            // 아무것도 적지 않는다. 「전부」 는 조건을 두지 않는 것이지 빈 목록이 아니다.
        }

        @Override
        public int nodeCount() {
            return 0;
        }

        @Override
        public int depth() {
            return 0;
        }
    }

    record And(List<FilterNode> children) implements FilterNode {
        public And {
            children = List.copyOf(children);
        }

        @Override
        public void appendTo(FilterSql out) {
            join(out, children, " AND ");
        }

        @Override
        public int nodeCount() {
            return 1 + children.stream().mapToInt(FilterNode::nodeCount).sum();
        }

        @Override
        public int depth() {
            return 1 + children.stream().mapToInt(FilterNode::depth).max().orElse(0);
        }
    }

    record Or(List<FilterNode> children) implements FilterNode {
        public Or {
            children = List.copyOf(children);
        }

        @Override
        public void appendTo(FilterSql out) {
            join(out, children, " OR ");
        }

        @Override
        public int nodeCount() {
            return 1 + children.stream().mapToInt(FilterNode::nodeCount).sum();
        }

        @Override
        public int depth() {
            return 1 + children.stream().mapToInt(FilterNode::depth).max().orElse(0);
        }
    }

    record Not(FilterNode child) implements FilterNode {
        @Override
        public void appendTo(FilterSql out) {
            out.append("NOT (");
            child.appendTo(out);
            out.append(")");
        }

        @Override
        public int nodeCount() {
            return 1 + child.nodeCount();
        }

        @Override
        public int depth() {
            return 1 + child.depth();
        }
    }

    /**
     * 칸 하나를 값과 견주는 잎.
     *
     * @param field    카탈로그를 거친 칸. <b>여기 임의 문자열이 올 수 없다</b>
     * @param operator 연산자
     * @param values   사람이 적은 그대로의 값들. 타입 변환은 적을 때 한다
     */
    record Compare(FilterableField field, FilterOperator operator,
                   List<String> values) implements FilterNode {

        public Compare {
            values = List.copyOf(values);
        }

        @Override
        public void appendTo(FilterSql out) {
            String column = field.sqlWith(out.alias());
            switch (operator) {
                case IN, NOT_IN -> {
                    out.append(column);
                    out.append(operator == FilterOperator.IN ? " IN (" : " NOT IN (");
                    for (int i = 0; i < values.size(); i++) {
                        if (i > 0) {
                            out.append(", ");
                        }
                        out.append(out.bind(typed(values.get(i), out)));
                    }
                    out.append(")");
                }
                case EQ -> binary(out, column, " = ");
                case NE -> binary(out, column, " <> ");
                case GT -> binary(out, column, " > ");
                case GTE -> binary(out, column, " >= ");
                case LT -> binary(out, column, " < ");
                case LTE -> binary(out, column, " <= ");
                case BETWEEN, NOT_BETWEEN -> {
                    out.append(column);
                    out.append(operator == FilterOperator.BETWEEN
                            ? " BETWEEN " : " NOT BETWEEN ");
                    out.append(out.bind(typed(values.get(0), out)));
                    out.append(" AND ");
                    out.append(out.bind(typed(values.get(1), out)));
                }
                case CONTAINS -> like(out, column, " LIKE ", "%%%s%%");
                case NOT_CONTAINS -> like(out, column, " NOT LIKE ", "%%%s%%");
                case STARTS_WITH -> like(out, column, " LIKE ", "%s%%");
                case ENDS_WITH -> like(out, column, " LIKE ", "%%%s");
                // PostgreSQL 의 대소문자 무시 정규식. 패턴 자체는 RegexGuard 가 저장 전에 본다.
                case MATCHES -> {
                    out.append(column);
                    out.append(" ~* ");
                    out.append(out.bind(values.get(0)));
                }
                // 자연키 컬럼은 NOT NULL DEFAULT '' 다. 「비었음」 은 둘을 함께 본다 —
                // 한쪽만 보면 원천에 따라 같은 뜻인데 다르게 걸린다.
                case IS_EMPTY -> out.append("coalesce(%s, '') = ''".formatted(column));
                case IS_NOT_EMPTY -> out.append("coalesce(%s, '') <> ''".formatted(column));
                case IS_TRUE -> out.append("%s IS TRUE".formatted(column));
                case IS_FALSE -> out.append("%s IS FALSE".formatted(column));
            }
        }

        private void binary(FilterSql out, String column, String op) {
            out.append(column);
            out.append(op);
            out.append(out.bind(typed(values.get(0), out)));
        }

        /**
         * {@code LIKE} 로 간다. <b>값 안의 {@code %} 와 {@code _} 는 글자로 다룬다</b> —
         * 그러지 않으면 품명에 든 「50%」 가 「무엇이든」 이 되어 엉뚱한 줄이 걸린다.
         */
        private void like(FilterSql out, String column, String op, String template) {
            String escaped = values.get(0)
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
            out.append(column);
            out.append(op);
            out.append(out.bind(template.formatted(escaped)));
            out.append(" ESCAPE '\\'");
        }

        /** 칸 타입에 맞는 값으로 바꾼다. 날짜는 이 회차의 기준 시각으로 푼다. */
        private Object typed(String raw, FilterSql out) {
            return switch (field.type()) {
                case NUMBER -> new BigDecimal(raw.trim());
                case DATE -> DateToken.parse(raw)
                        .orElseThrow(() -> new IllegalStateException(
                                "읽을 수 없는 날짜가 저장되어 있다: " + raw))
                        .resolve(out.asOf());
                case TEXT, BOOL -> raw;
            };
        }

        @Override
        public int nodeCount() {
            return 1;
        }

        @Override
        public int depth() {
            return 1;
        }
    }

    private static void join(FilterSql out, List<FilterNode> children, String glue) {
        out.append("(");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                out.append(glue);
            }
            children.get(i).appendTo(out);
        }
        out.append(")");
    }
}
```

- [ ] **Step 5: `FilterSpec` 을 만든다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import java.time.Instant;
import java.util.Map;

/**
 * 한 원천에서 <b>무엇을 볼지</b>.
 *
 * <p>{@code WarehouseScope} 를 대신한다. 창고 하나만 고를 수 있던 것을 어느 칸으로든 걸 수
 * 있게 넓힌 것이고, 창고는 이 안의 조건 한 줄이 되었다.
 *
 * <p><b>왜 값 객체로 두는가.</b> 조건이 걸려야 하는 쿼리가 한 곳이 아니다 — 합계·미매칭·
 * 뜯어보기·품목 묶기. 쿼리마다 따로 적으면 한 곳이 빠지고, <b>빠진 곳만 다른 숫자를 낸다.</b>
 * 그러면 「합계는 이런데 뜯어보면 다르다」 가 되어 어느 쪽을 믿어야 할지 알 수 없다.
 */
public record FilterSpec(FilterNode root) {

    /** 기본 바인딩 접두어. 좌·우를 한 쿼리에 걸 때는 서로 다른 값을 준다. */
    public static final String PREFIX = "f";

    private static final FilterSpec ALL = new FilterSpec(FilterNode.ALL);

    public FilterSpec {
        root = root == null ? FilterNode.ALL : root;
    }

    /** 아무것도 거르지 않는 조건. */
    public static FilterSpec all() {
        return ALL;
    }

    public boolean isAll() {
        return root.isAll();
    }

    /** 조각과 값을 한 번에. 두 번 불러도 같은 결과다. */
    public Compiled compile(String alias, String prefix, Instant asOf) {
        if (isAll()) {
            return new Compiled("", Map.of());
        }
        FilterSql out = new FilterSql(alias, prefix, asOf);
        out.append(" AND ");
        root.appendTo(out);
        return new Compiled(out.sql(), out.params());
    }

    /**
     * 쿼리에 끼울 조건 조각.
     *
     * <p>비어 있으면 <b>빈 문자열</b>을 준다. {@code IN ()} 은 SQL 문법 오류라 「전부」 를
     * 빈 목록으로 표현할 수 없기 때문이다.
     */
    public String sqlAnd(String alias, String prefix, Instant asOf) {
        return compile(alias, prefix, asOf).sql();
    }

    public String sqlAnd(String alias, Instant asOf) {
        return sqlAnd(alias, PREFIX, asOf);
    }

    /** 바인딩할 값. 조각에 없는 파라미터를 넘기면 바인딩에서 거부당하므로 비면 빈 맵이다. */
    public Map<String, Object> params(String prefix, Instant asOf) {
        return compile("s", prefix, asOf).params();
    }

    public Map<String, Object> params(Instant asOf) {
        return params(PREFIX, asOf);
    }

    /** 화면에 보여줄 말. 「전체」 인지 무엇이 걸렸는지가 한눈에 보여야 잘못 걸린 것을 알아챈다. */
    public String describe() {
        return isAll() ? "전체" : ExpressionWriter.write(root);
    }

    /** 컴파일 결과. 조각과 값은 언제나 함께 다닌다. */
    public record Compiled(String sql, Map<String, Object> params) {
    }
}
```

> `describe()` 가 `ExpressionWriter` 를 부른다. **Task 7 에서 만든다.** 이 과제에서는 임시로
> `return isAll() ? "전체" : "조건 " + root.nodeCount() + "개";` 로 두고, Task 7 에서 바꾼다.
> 컴파일이 되어야 시험이 돌기 때문이다.

- [ ] **Step 6: 시험을 돌려 통과를 확인한다**

Run: `./gradlew :palim-reconcile:test --tests "*FilterSpecTest*"`
Expected: PASS (12건)

- [ ] **Step 7: 커밋**

```bash
git add palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterSql.java \
        palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterNode.java \
        palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterSpec.java \
        palim-reconcile/src/test/java/kr/suhsaechan/palim/reconcile/filter/FilterSpecTest.java
git commit -m "창고 말고 다른 조건으로는 대조를 좁힐 수가 없다 : feat : 조건을 나무로 두고 SQL 을 그 나무에서만 만들어 값이 SQL 이 될 길을 없앰 https://github.com/Cassiiopeia/palim/issues/161"
```

---

### Task 4: 저장과 창고 설정 이관

조건 줄을 표에 담고, 이미 설정해 둔 창고를 조건 줄로 옮긴다. **옛 컬럼은 지우지 않는다** — 이관이 잘못됐을 때 원본을 볼 곳이 있어야 하고, 컬럼을 지우는 것은 되돌릴 수 없다.

여기서부터 Testcontainers(PostgreSQL **14**)가 필요하다.

**Files:**
- Create: `palim-app/src/main/resources/db/migration/V34__reconcile_filter.sql`
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterSide.java`
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterRow.java`
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterRowRepository.java`
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterCompiler.java`
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/FilterStoreIntegrationTest.java`

**Interfaces:**
- Consumes: `FilterableField`·`FieldCatalog` (T1), `FilterOperator` (T2), `FilterNode`·`FilterSpec` (T3)
- Produces:
  - `enum FilterSide { LEFT, RIGHT }`
  - `class FilterRow extends BaseTimeEntity` — `static FilterRow field(UUID tenantId, UUID definitionId, FilterSide side, int ordinal, String fieldKey, FilterOperator operator, List<String> values)`, `static FilterRow expression(UUID tenantId, UUID definitionId, FilterSide side, int ordinal, String text)`, 접근자 `getSide()`·`getOrdinal()`·`getRowType()`·`getFieldKey()`·`getOperator()`·`getValues()`·`getExpression()`
  - `interface FilterRowRepository extends JpaRepository<FilterRow, UUID>` — `List<FilterRow> findByDefinitionIdOrderBySideAscOrdinalAsc(UUID definitionId)`, `void deleteByDefinitionIdAndSide(UUID definitionId, FilterSide side)`
  - `class FilterCompiler` — `FilterSpec compile(List<FilterRow> rows)` (한 side 분)

- [ ] **Step 1: 실패하는 통합 시험을 쓴다**

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.filter.FilterCompiler;
import kr.suhsaechan.palim.reconcile.filter.FilterOperator;
import kr.suhsaechan.palim.reconcile.filter.FilterRow;
import kr.suhsaechan.palim.reconcile.filter.FilterRowRepository;
import kr.suhsaechan.palim.reconcile.filter.FilterSide;
import kr.suhsaechan.palim.reconcile.filter.FilterSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 조건 줄을 담고 다시 읽는다.
 *
 * <p>창고만 고를 수 있던 시절의 설정이 <b>그대로 살아나야</b> 한다. 이관에서 조건이 사라지면
 * 대조는 다음 날 아침부터 전 창고를 더하는데, 화면은 아무 말도 하지 않는다.
 */
class FilterStoreIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final Instant AS_OF = Instant.parse("2026-08-22T03:00:00Z");

    @Autowired private FilterRowRepository rows;
    @Autowired private FilterCompiler compiler;
    @Autowired private JdbcClient jdbcClient;

    private UUID definitionId;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        definitionId = UUID.randomUUID();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("조건 줄을 담고 다시 읽으면 값이 그대로다")
    void storesAndReads() {
        rows.save(FilterRow.field(TENANT, definitionId, FilterSide.LEFT, 0,
                "warehouse_code", FilterOperator.IN, List.of("01", "02")));
        rows.save(FilterRow.field(TENANT, definitionId, FilterSide.LEFT, 1,
                "quality_status", FilterOperator.NOT_IN, List.of("불량")));

        List<FilterRow> loaded = rows.findByDefinitionIdOrderBySideAscOrdinalAsc(definitionId);

        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).getValues()).containsExactly("01", "02");
        assertThat(loaded.get(1).getOperator()).isEqualTo(FilterOperator.NOT_IN);
    }

    @Test
    @DisplayName("여러 줄은 AND 로 묶인다")
    void compilesRowsToAnd() {
        List<FilterRow> loaded = List.of(
                FilterRow.field(TENANT, definitionId, FilterSide.LEFT, 0,
                        "warehouse_code", FilterOperator.IN, List.of("01")),
                FilterRow.field(TENANT, definitionId, FilterSide.LEFT, 1,
                        "quality_status", FilterOperator.EQ, List.of("정상")));

        FilterSpec spec = compiler.compile(loaded);

        assertThat(spec.sqlAnd("s", "f", AS_OF))
                .isEqualTo(" AND (s.warehouse_code IN (:f0) AND s.quality_status = :f1)");
    }

    @Test
    @DisplayName("줄이 없으면 전부 본다 — 지금까지의 동작이다")
    void emptyRowsMeanAll() {
        assertThat(compiler.compile(List.of()).isAll()).isTrue();
    }

    @Test
    @DisplayName("카탈로그에 없는 칸이 저장되어 있으면 그 줄을 버리지 않고 드러낸다")
    void unknownFieldIsReported() {
        List<FilterRow> loaded = List.of(FilterRow.field(TENANT, definitionId,
                FilterSide.LEFT, 0, "no_such_column", FilterOperator.EQ, List.of("x")));

        assertThatThrownBy(() -> compiler.compile(loaded))
                .isInstanceOf(kr.suhsaechan.palim.common.error.BusinessException.class);
    }

    @Test
    @DisplayName("정의를 지우면 조건도 함께 사라진다 — 매달리지 않은 줄이 남으면 안 된다")
    void cascadesOnDefinitionDelete() {
        UUID realDefinition = insertDefinition();
        rows.save(FilterRow.field(TENANT, realDefinition, FilterSide.LEFT, 0,
                "warehouse_code", FilterOperator.IN, List.of("01")));

        jdbcClient.sql("DELETE FROM reconcile_definition WHERE id = :id")
                .param("id", realDefinition).update();

        assertThat(rows.findByDefinitionIdOrderBySideAscOrdinalAsc(realDefinition)).isEmpty();
    }

    @Test
    @DisplayName("옛 창고 설정이 조건 줄로 옮겨져 있다")
    void migratesWarehouseCsv() {
        // V34 가 도는 시점에 이미 있던 정의를 흉내 낸다. 마이그레이션은 시험 컨테이너가
        // 뜰 때 이미 돌았으므로, 여기서는 «이관 SQL 과 같은 문장» 이 옳은 줄을 만드는지 본다.
        UUID legacy = insertDefinition("01,02", "99");

        jdbcClient.sql("""
                        INSERT INTO reconcile_filter
                            (id, tenant_id, definition_id, side, ordinal, row_type,
                             field_key, operator, values_json, created_at, updated_at)
                        SELECT gen_random_uuid(), d.tenant_id, d.id, 'LEFT', 0,
                               'FIELD', 'warehouse_code', 'IN',
                               to_jsonb(string_to_array(d.left_warehouses, ',')), now(), now()
                          FROM reconcile_definition d
                         WHERE d.id = :id AND d.left_warehouses IS NOT NULL
                           AND d.left_warehouses <> ''
                        """)
                .param("id", legacy).update();

        List<FilterRow> loaded = rows.findByDefinitionIdOrderBySideAscOrdinalAsc(legacy);

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getFieldKey()).isEqualTo("warehouse_code");
        assertThat(loaded.get(0).getOperator()).isEqualTo(FilterOperator.IN);
        assertThat(loaded.get(0).getValues()).containsExactly("01", "02");
    }

    private UUID insertDefinition() {
        return insertDefinition(null, null);
    }

    private UUID insertDefinition(String leftWarehouses, String rightWarehouses) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO reconcile_definition
                            (id, tenant_id, code, name, left_source, right_source, target_table,
                             compare_field, tolerance, base_at_granularity, is_active,
                             breakdown_axis, unit_name_rule,
                             left_warehouses, right_warehouses, created_at, updated_at)
                        VALUES (:id, :tenant, :code, '시험 대조', 'left-src', 'right-src',
                                'std_stock_snapshot', 'base_quantity', 0, 'DAY', true,
                                'NAME', 'COMMON', :left, :right, now(), now())
                        """)
                .param("id", id)
                .param("tenant", TENANT)
                .param("code", "DEF-" + id.toString().substring(0, 8))
                .param("left", leftWarehouses)
                .param("right", rightWarehouses)
                .update();
        return id;
    }
}
```

> `assertThatThrownBy` 를 쓰므로 `import static org.assertj.core.api.Assertions.assertThatThrownBy;` 를 함께 넣는다.

- [ ] **Step 2: 시험을 돌려 실패를 확인한다**

Run: `./gradlew :palim-app:test --tests "*FilterStoreIntegrationTest*"`
Expected: 컴파일 실패 — `FilterRow` 등이 없음

- [ ] **Step 3: 마이그레이션 `V34` 를 쓴다**

```sql
-- 대조가 «무엇을 볼지» 거르는 조건.
--
-- 창고 하나만 고를 수 있던 것(V30)을 어느 칸으로든 걸 수 있게 넓힌다. 걸러야 하는 것은 창고만이
-- 아니다 — 불량 재고, 유통기한이 지난 것, 특정 로트, 원천이 주는 고유 구분값. 칸마다 컬럼과
-- 화면을 새로 만들면 다음 요구에서 또 막힌다.
--
-- **값은 언제나 배열이다.** EQ 는 원소 하나, BETWEEN 은 둘, IS_EMPTY 는 없음. 모양이 하나면
-- 화면·검증·SQL 조립이 전부 한 갈래로 끝난다. 연산자마다 저장 모양이 다르면 그 조합만큼 분기가
-- 생기고, 안 쓰는 분기부터 썩는다.
--
-- row_type 이 FIELD(조건 줄)와 EXPRESSION(식)을 가른다. 식은 field_key·operator 를 쓰지 않고
-- values_json 에 글 하나를 담는다. 둘을 한 표에 두는 이유는 순서(ordinal)와 좌우(side)가 같은
-- 개념이기 때문이고, 표가 갈리면 「어느 쪽이 먼저인가」 를 두 곳에서 맞춰야 한다.
--
-- ON DELETE CASCADE — 정의를 지울 때 조건이 남지 않게 한다(#150 에서 매달리지 않은 자료가
-- 남는 문제를 이미 겪었다).
CREATE TABLE reconcile_filter
(
    id            uuid         NOT NULL,
    tenant_id     uuid         NOT NULL,
    definition_id uuid         NOT NULL,
    side          varchar(10)  NOT NULL,
    ordinal       integer      NOT NULL,
    row_type      varchar(20)  NOT NULL DEFAULT 'FIELD',
    field_key     varchar(200) NOT NULL DEFAULT '',
    operator      varchar(20)  NOT NULL DEFAULT '',
    values_json   jsonb        NOT NULL DEFAULT '[]'::jsonb,
    created_at    timestamptz,
    updated_at    timestamptz,
    CONSTRAINT pk_reconcile_filter PRIMARY KEY (id),
    CONSTRAINT fk_reconcile_filter_definition
        FOREIGN KEY (definition_id) REFERENCES reconcile_definition (id) ON DELETE CASCADE
);

CREATE INDEX ix_reconcile_filter_definition
    ON reconcile_filter (definition_id, side, ordinal);

COMMENT ON COLUMN reconcile_filter.side IS 'LEFT | RIGHT — 어느 원천에 거는 조건인가';
COMMENT ON COLUMN reconcile_filter.row_type IS 'FIELD(조건 줄) | EXPRESSION(식)';
COMMENT ON COLUMN reconcile_filter.field_key IS
    '걸 칸. 표준 칸 이름 또는 attributes.«키». 카탈로그에 없으면 실행을 거부한다';
COMMENT ON COLUMN reconcile_filter.values_json IS
    '값. 언제나 배열. EQ 는 1개, BETWEEN 은 2개, IS_EMPTY 는 0개. 식은 글 하나';

-- 이미 설정해 둔 창고를 조건 줄로 옮긴다. 옮기지 않으면 다음 날 아침부터 전 창고를 더하는데
-- 화면은 아무 말도 하지 않는다.
--
-- gen_random_uuid() 는 PG13 부터 core 에 있다 — 운영은 14 라 확장 없이 쓸 수 있다.
INSERT INTO reconcile_filter (id, tenant_id, definition_id, side, ordinal, row_type,
                              field_key, operator, values_json, created_at, updated_at)
SELECT gen_random_uuid(), d.tenant_id, d.id, 'LEFT', 0,
       'FIELD', 'warehouse_code', 'IN',
       to_jsonb(string_to_array(d.left_warehouses, ',')), now(), now()
  FROM reconcile_definition d
 WHERE d.left_warehouses IS NOT NULL AND d.left_warehouses <> '';

INSERT INTO reconcile_filter (id, tenant_id, definition_id, side, ordinal, row_type,
                              field_key, operator, values_json, created_at, updated_at)
SELECT gen_random_uuid(), d.tenant_id, d.id, 'RIGHT', 0,
       'FIELD', 'warehouse_code', 'IN',
       to_jsonb(string_to_array(d.right_warehouses, ',')), now(), now()
  FROM reconcile_definition d
 WHERE d.right_warehouses IS NOT NULL AND d.right_warehouses <> '';

-- reconcile_definition.left_warehouses / right_warehouses 는 **지우지 않는다.**
-- 이관이 잘못됐을 때 원본을 볼 곳이 있어야 하고, 컬럼을 지우는 것은 되돌릴 수 없다.
-- 대신 코드가 더는 읽지 않는다.
COMMENT ON COLUMN reconcile_definition.left_warehouses IS
    '더는 쓰지 않는다. V34 에서 reconcile_filter 로 옮겼다. 이관 확인용으로만 남긴다';
COMMENT ON COLUMN reconcile_definition.right_warehouses IS
    '더는 쓰지 않는다. V34 에서 reconcile_filter 로 옮겼다. 이관 확인용으로만 남긴다';
```

- [ ] **Step 4: `FilterSide` 와 `FilterRow` 를 만든다**

```java
package kr.suhsaechan.palim.reconcile.filter;

/** 어느 원천에 거는 조건인가. */
public enum FilterSide {
    LEFT,
    RIGHT
}
```

```java
package kr.suhsaechan.palim.reconcile.filter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.tenant.TenantFilters;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 저장된 조건 한 줄.
 *
 * <p><b>값은 언제나 배열이다.</b> {@code EQ} 는 원소 하나, {@code BETWEEN} 은 둘,
 * {@code IS_EMPTY} 는 없음. 모양이 하나면 화면·검증·SQL 조립이 전부 한 갈래로 끝난다.
 *
 * <p>식({@code EXPRESSION})도 같은 표에 담는다. 순서와 좌우가 조건 줄과 같은 개념이라
 * 표를 가르면 「어느 쪽이 먼저인가」 를 두 곳에서 맞춰야 한다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "reconcile_filter")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FilterRow extends BaseTimeEntity {

    /** 조건 줄. */
    public static final String TYPE_FIELD = "FIELD";
    /** 식. {@code values_json} 에 글 하나가 든다. */
    public static final String TYPE_EXPRESSION = "EXPRESSION";

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID definitionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FilterSide side;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, length = 20)
    private String rowType;

    @Column(nullable = false, length = 200)
    private String fieldKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FilterOperator operator;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "values_json", nullable = false, columnDefinition = "jsonb")
    private List<String> values;

    private FilterRow(UUID tenantId, UUID definitionId, FilterSide side, int ordinal,
                      String rowType, String fieldKey, FilterOperator operator,
                      List<String> values) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.definitionId = definitionId;
        this.side = side;
        this.ordinal = ordinal;
        this.rowType = rowType;
        this.fieldKey = fieldKey;
        this.operator = operator;
        this.values = values == null ? List.of() : List.copyOf(values);
    }

    /** 조건 줄 하나. */
    public static FilterRow field(UUID tenantId, UUID definitionId, FilterSide side,
                                  int ordinal, String fieldKey, FilterOperator operator,
                                  List<String> values) {
        return new FilterRow(tenantId, definitionId, side, ordinal,
                TYPE_FIELD, fieldKey, operator, values);
    }

    /**
     * 식 한 줄. 한 side 에 하나만 둔다.
     *
     * <p>{@code operator} 는 쓰이지 않지만 {@code NOT NULL} 이라 아무 값이나 채운다 —
     * enum 컬럼에 빈 문자열을 넣으면 읽을 때 터진다.
     */
    public static FilterRow expression(UUID tenantId, UUID definitionId, FilterSide side,
                                       int ordinal, String text) {
        return new FilterRow(tenantId, definitionId, side, ordinal,
                TYPE_EXPRESSION, "", FilterOperator.EQ, List.of(text));
    }

    public boolean isExpression() {
        return TYPE_EXPRESSION.equals(rowType);
    }

    /** 식의 글. 조건 줄이면 빈 문자열. */
    @JsonIgnore
    public String getExpression() {
        return isExpression() && !values.isEmpty() ? values.get(0) : "";
    }
}
```

> `@JdbcTypeCode(SqlTypes.JSON)` 로 `List<String>` ↔ `jsonb` 를 잇는다. Hibernate 6 부터 기본
> 제공이라 별도 의존성이 필요 없다. 마이그레이션의 `to_jsonb(string_to_array(...))` 가 만드는
> 것도 문자열 배열이라 모양이 같다.

- [ ] **Step 5: `FilterRowRepository` 와 `FilterCompiler` 를 만든다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FilterRowRepository extends JpaRepository<FilterRow, UUID> {

    List<FilterRow> findByDefinitionIdOrderBySideAscOrdinalAsc(UUID definitionId);

    void deleteByDefinitionIdAndSide(UUID definitionId, FilterSide side);
}
```

```java
package kr.suhsaechan.palim.reconcile.filter;

import java.util.ArrayList;
import java.util.List;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 저장된 줄을 나무로 바꾼다.
 *
 * <p>조건 줄은 <b>AND 로 묶인다.</b> 한 줄 안의 여러 값은 이미 OR 이라 흔한 경우는 줄로 덮이고,
 * 칸을 넘는 OR 이 필요하면 식을 쓴다.
 *
 * <p><b>카탈로그에 없는 칸은 조용히 건너뛰지 않는다.</b> 건너뛰면 조건이 빠진 채로 대조가 돌아
 * 틀린 답을 내는데, 화면은 「조건이 걸려 있다」 고 보인다. 원천 구성이 바뀌어 칸이 사라진
 * 것이므로 사람이 고쳐야 할 일이다.
 */
@Component
public class FilterCompiler {

    /** 한 side 의 줄들을 하나의 조건으로. 비면 「전부」. */
    public FilterSpec compile(List<FilterRow> rows) {
        List<FilterNode> nodes = new ArrayList<>();
        for (FilterRow row : rows) {
            if (row.isExpression()) {
                // 식은 Task 7 에서 파서가 붙는다. 그전까지는 저장만 되고 무시된다.
                continue;
            }
            nodes.add(toCompare(row));
        }
        if (nodes.isEmpty()) {
            return FilterSpec.all();
        }
        return new FilterSpec(nodes.size() == 1 ? nodes.get(0) : new FilterNode.And(nodes));
    }

    private FilterNode.Compare toCompare(FilterRow row) {
        FilterableField field = FieldCatalog.find(row.getFieldKey())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.FILTER_FIELD_UNKNOWN, row.getFieldKey()));
        if (!row.getOperator().supports(field.type())) {
            throw new BusinessException(ErrorCode.FILTER_OPERATOR_MISMATCH,
                    row.getOperator().label(), field.label());
        }
        if (!row.getOperator().acceptsCount(row.getValues().size())) {
            throw new BusinessException(ErrorCode.FILTER_VALUE_COUNT,
                    row.getOperator().label(), row.getValues().size());
        }
        return new FilterNode.Compare(field, row.getOperator(), row.getValues());
    }
}
```

- [ ] **Step 6: `ErrorCode` 세 줄과 메시지를 더한다**

`palim-common/src/main/java/kr/suhsaechan/palim/common/error/ErrorCode.java` 의 `NORMALIZATION_RULE_INVALID("R006", …)` 뒤에 붙인다.

```java
    /**
     * 조건이 가리키는 칸이 카탈로그에 없다.
     *
     * <p>원천 구성이 바뀌어 그 칸이 사라졌거나, 손으로 고친 값이 들어왔다. <b>조용히 건너뛰지
     * 않는다</b> — 건너뛰면 조건이 빠진 채로 대조가 도는데 화면은 걸려 있다고 보인다.
     */
    FILTER_FIELD_UNKNOWN("R011", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.WARN),

    /** 그 칸에 쓸 수 없는 연산자다. 글 칸에 「사이」 를 걸려는 식. */
    FILTER_OPERATOR_MISMATCH("R012", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.DEBUG),

    /** 연산자가 요구하는 값 개수와 다르다. 값이 0개인 「이것만」 은 IN () 이 되어 문법 오류다. */
    FILTER_VALUE_COUNT("R013", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),
```

`errors.properties`:

```properties
error.FILTER_FIELD_UNKNOWN=«{0}» 칸이 지금 담긴 자료에 없습니다. 조건을 다시 골라 주세요.
error.FILTER_OPERATOR_MISMATCH=«{1}» 칸에는 «{0}» 를 쓸 수 없습니다.
error.FILTER_VALUE_COUNT=«{0}» 는 값 개수가 맞지 않습니다({1}개). 값을 다시 골라 주세요.
```

`errors_en.properties`:

```properties
error.FILTER_FIELD_UNKNOWN=Field "{0}" is not present in the loaded data. Please pick the condition again.
error.FILTER_OPERATOR_MISMATCH=Operator "{0}" cannot be used on field "{1}".
error.FILTER_VALUE_COUNT=Operator "{0}" got {1} value(s), which is not a valid count.
```

- [ ] **Step 7: 시험을 돌려 통과를 확인한다**

Run: `./gradlew :palim-app:test --tests "*FilterStoreIntegrationTest*" --tests "*ErrorCodeIntegrationTest*"`
Expected: PASS (6건 + 기존 `ErrorCodeIntegrationTest` 가 새 코드의 메시지 누락을 잡지 않고 통과)

- [ ] **Step 8: 커밋**

```bash
git add palim-app/src/main/resources/db/migration/V34__reconcile_filter.sql \
        palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/ \
        palim-common/src/main/java/kr/suhsaechan/palim/common/error/ErrorCode.java \
        palim-common/src/main/resources/errors.properties \
        palim-common/src/main/resources/errors_en.properties \
        palim-app/src/test/java/kr/suhsaechan/palim/integration/FilterStoreIntegrationTest.java
git commit -m "창고 말고 다른 조건으로는 대조를 좁힐 수가 없다 : feat : 조건을 표에 담고 이미 골라 둔 창고를 조건 줄로 옮김 https://github.com/Cassiiopeia/palim/issues/161"
```

---

### Task 5: 조회를 새 조건에 잇고 `WarehouseScope` 를 없앤다

네 쿼리(합계·미매칭·뜯어보기·품목 묶기)가 **같은 조건**을 본다. 하나라도 빠지면 그곳만 다른 숫자를 내고, 그러면 「합계는 이런데 뜯어보면 다르다」 가 되어 어느 쪽을 믿을지 알 수 없다.

`WarehouseScope` 는 **삭제한다.** 남겨 두면 두 벌이 되어 한쪽만 고쳐지는 날이 온다.

**Files:**
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterService.java`
- Modify: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/define/Pairing.java`
- Modify: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/engine/SnapshotAggregator.java`
- Modify: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/engine/ReconcileEngine.java:117-125,224`
- Modify: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/match/MatchBoard.java:107,310-316`
- Modify: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/define/ReconcileDefinition.java` — `leftScope()`·`rightScope()`·`changeWarehouses()`·`leftWarehouses`·`rightWarehouses` 필드 제거
- Modify: `palim-web/src/main/java/kr/suhsaechan/palim/web/reconcile/ReconcileController.java`·`UnitController.java` — `Pairing.of(definition)` 호출부를 `filters.pairingOf(definition)` 으로
- Delete: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/define/WarehouseScope.java`
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/FilterQueryIntegrationTest.java`
- Modify: `palim-app/src/test/java/kr/suhsaechan/palim/integration/ReconcileEngineIntegrationTest.java` — `WarehouseScope` 를 쓰던 시험을 새 방식으로

**Interfaces:**
- Consumes: `FilterSpec` (T3), `FilterRowRepository`·`FilterCompiler`·`FilterSide` (T4)
- Produces:
  - `class FilterService` — `FilterSpec specOf(UUID definitionId, FilterSide side)`, `Pairing pairingOf(ReconcileDefinition definition)`, `List<FilterRow> rowsOf(UUID definitionId, FilterSide side)`
  - `record Pairing(String leftSource, String rightSource, FilterSpec leftFilter, FilterSpec rightFilter, String compareField)`
    - `static Pairing ofSources(String leftSource, String rightSource)`
    - `FilterSpec filterOf(String source)`
    - `boolean needsChoice(int leftWarehouseCount, int rightWarehouseCount)`
  - `SnapshotAggregator.sumByUnit(UUID, String, Instant, String, FilterSpec)`
  - `SnapshotAggregator.unmatched(UUID, String, Instant, String, FilterSpec)`
  - `MatchBoard.findItem(UUID, String, String, FilterSpec)`

- [ ] **Step 1: 실패하는 통합 시험을 쓴다**

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.Pairing;
import kr.suhsaechan.palim.reconcile.engine.SnapshotAggregator;
import kr.suhsaechan.palim.reconcile.filter.FieldCatalog;
import kr.suhsaechan.palim.reconcile.filter.FilterNode;
import kr.suhsaechan.palim.reconcile.filter.FilterOperator;
import kr.suhsaechan.palim.reconcile.filter.FilterSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 조건이 <b>네 쿼리 모두</b>에 같게 걸린다.
 *
 * <p>한 곳이 빠지면 그곳만 다른 숫자를 낸다. 그러면 「합계는 이런데 뜯어보면 다르다」 가 되어
 * 어느 쪽을 믿어야 할지 알 수 없다 — 실제로 그렇게 한 번 겪었다(#147).
 */
class FilterQueryIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private SnapshotAggregator aggregator;
    @Autowired private JdbcClient jdbcClient;

    private Instant baseAt;
    private String source;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        baseAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        source = "src-" + UUID.randomUUID().toString().substring(0, 6);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static FilterSpec spec(String key, FilterOperator op, String... values) {
        return new FilterSpec(new FilterNode.Compare(
                FieldCatalog.find(key).orElseThrow(), op, List.of(values)));
    }

    @Test
    @DisplayName("창고 조건이 미매칭 조회에 걸린다")
    void filtersUnmatched() {
        snapshot("A", "100", "01", "정상");
        snapshot("B", "200", "02", "정상");

        var all = aggregator.unmatched(TENANT, source, baseAt, "base_quantity",
                FilterSpec.all());
        var onlyFirst = aggregator.unmatched(TENANT, source, baseAt, "base_quantity",
                spec("warehouse_code", FilterOperator.IN, "01"));

        assertThat(all).hasSize(2);
        assertThat(onlyFirst).hasSize(1);
        assertThat(onlyFirst.get(0).quantity()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("창고가 아닌 칸으로도 거를 수 있다 — 이것이 이 작업의 목적이다")
    void filtersByNonWarehouseField() {
        snapshot("A", "100", "01", "정상");
        snapshot("B", "200", "01", "불량");

        var normalOnly = aggregator.unmatched(TENANT, source, baseAt, "base_quantity",
                spec("quality_status", FilterOperator.NOT_IN, "불량"));

        assertThat(normalOnly).hasSize(1);
        assertThat(normalOnly.get(0).quantity()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("원천 고유 칸(attributes)으로도 거를 수 있다")
    void filtersByAttribute() {
        snapshotWithAttributes("A", "100", "{\"재고구분\": \"정상\"}");
        snapshotWithAttributes("B", "200", "{\"재고구분\": \"보류\"}");

        var held = aggregator.unmatched(TENANT, source, baseAt, "base_quantity",
                spec("attributes.재고구분", FilterOperator.IN, "보류"));

        assertThat(held).hasSize(1);
        assertThat(held.get(0).quantity()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("조건이 없으면 지금까지와 같은 답이 나온다")
    void noFilterMeansEverything() {
        snapshot("A", "100", "01", "정상");
        snapshot("B", "200", "02", "불량");

        assertThat(aggregator.unmatched(TENANT, source, baseAt, "base_quantity",
                FilterSpec.all())).hasSize(2);
    }

    @Test
    @DisplayName("한쪽 원천만 걸어도 다른 쪽이 안 물든다")
    void sidesStayApart() {
        Pairing pairing = new Pairing("left-src", "right-src",
                spec("warehouse_code", FilterOperator.IN, "01"),
                FilterSpec.all(), "base_quantity");

        assertThat(pairing.filterOf("left-src").isAll()).isFalse();
        assertThat(pairing.filterOf("right-src").isAll()).isTrue();
    }

    private void snapshot(String itemRef, String qty, String warehouse, String quality) {
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             quality_status, quantity, base_quantity, base_unit, raw_item_name,
                             created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, :warehouse, '',
                                :quality, :qty, :qty, 'EA', :name, :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", baseAt.atOffset(ZoneOffset.UTC))
                .param("source", source)
                .param("warehouse", warehouse)
                .param("quality", quality)
                .param("qty", new BigDecimal(qty))
                .param("name", "품목 " + itemRef)
                .update();
    }

    private void snapshotWithAttributes(String itemRef, String qty, String attributesJson) {
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             quantity, base_quantity, base_unit, raw_item_name, attributes,
                             created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, '', '',
                                :qty, :qty, 'EA', :name, cast(:attrs as jsonb), :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", baseAt.atOffset(ZoneOffset.UTC))
                .param("source", source)
                .param("qty", new BigDecimal(qty))
                .param("name", "품목 " + itemRef)
                .param("attrs", attributesJson)
                .update();
    }
}
```

- [ ] **Step 2: 시험을 돌려 실패를 확인한다**

Run: `./gradlew :palim-app:test --tests "*FilterQueryIntegrationTest*"`
Expected: 컴파일 실패 — `Pairing` 이 아직 `WarehouseScope` 를 든다

- [ ] **Step 3: `FilterService` 를 만든다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.reconcile.define.Pairing;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조건을 읽어 오는 <b>한 자리</b>.
 *
 * <p>화면도 엔진도 여기로만 조건을 얻는다. 두 곳이 각자 읽으면 한쪽이 식을 빠뜨리거나 정렬을
 * 다르게 하는 날이 오고, 그 차이는 숫자로만 드러난다.
 */
@Service
@RequiredArgsConstructor
public class FilterService {

    private final FilterRowRepository rows;
    private final FilterCompiler compiler;

    /** 그 원천 쪽 조건 줄. 화면이 편집기를 그리는 데 쓴다. */
    @Transactional(readOnly = true)
    public List<FilterRow> rowsOf(UUID definitionId, FilterSide side) {
        return rows.findByDefinitionIdOrderBySideAscOrdinalAsc(definitionId).stream()
                .filter(row -> row.getSide() == side)
                .toList();
    }

    /** 그 원천 쪽 조건. 비어 있으면 전부 본다. */
    @Transactional(readOnly = true)
    public FilterSpec specOf(UUID definitionId, FilterSide side) {
        return compiler.compile(rowsOf(definitionId, side));
    }

    /**
     * 견주는 방식 한 묶음.
     *
     * <p>{@code Pairing} 을 만드는 길을 여기 하나로 둔다. 새 조회를 만들 때 원천을 넘기는
     * 순간 조건도 함께 넘어가므로 <b>빠뜨릴 수가 없다</b> — 이것이 이 타입의 존재 이유다.
     */
    @Transactional(readOnly = true)
    public Pairing pairingOf(ReconcileDefinition definition) {
        return new Pairing(definition.getLeftSource(), definition.getRightSource(),
                specOf(definition.getId(), FilterSide.LEFT),
                specOf(definition.getId(), FilterSide.RIGHT),
                definition.getCompareField());
    }
}
```

- [ ] **Step 4: `Pairing` 을 바꾼다**

`WarehouseScope leftScope, WarehouseScope rightScope` 를 `FilterSpec leftFilter, FilterSpec rightFilter` 로 바꾼다. `Pairing.of(ReconcileDefinition)` 은 **삭제한다** — 조건을 읽으려면 저장소가 필요하고, 그것은 `FilterService.pairingOf` 의 일이다. 정적 팩토리를 남기면 조건 없는 `Pairing` 이 조용히 만들어진다.

```java
public record Pairing(String leftSource, String rightSource,
                      FilterSpec leftFilter, FilterSpec rightFilter,
                      String compareField) {

    public Pairing {
        leftFilter = leftFilter == null ? FilterSpec.all() : leftFilter;
        rightFilter = rightFilter == null ? FilterSpec.all() : rightFilter;
        compareField = CompareField.sanitize(compareField);
    }

    /**
     * 조건을 가리지 않는 짝.
     *
     * <p>정의가 아직 없는 자리(설정 안내, 시험)에서만 쓴다. 대조 화면이 이것을 쓰면 조건을
     * 건 뜻이 사라진다.
     */
    public static Pairing ofSources(String leftSource, String rightSource) {
        return new Pairing(leftSource, rightSource, FilterSpec.all(), FilterSpec.all(), null);
    }

    /** 그 원천에 걸린 조건. */
    public FilterSpec filterOf(String source) {
        return leftSource.equals(source) ? leftFilter : rightFilter;
    }

    /**
     * 한쪽에 창고가 여럿인데 <b>아무 조건도 안 건</b> 상태인가.
     *
     * <p>그대로 두면 맡기지 않은 물량까지 합산되어 조용히 틀린 답이 나온다. 화면이 이때
     * 「조건을 거세요」 를 띄운다.
     */
    public boolean needsChoice(int leftWarehouseCount, int rightWarehouseCount) {
        return (leftWarehouseCount > 1 && leftFilter.isAll())
                || (rightWarehouseCount > 1 && rightFilter.isAll());
    }
}
```

- [ ] **Step 5: `SnapshotAggregator` 의 두 조회를 바꾼다**

`WarehouseScope scope` 를 `FilterSpec filter` 로 바꾸고, 조각·바인딩에 기준 시각을 넘긴다. `sumByUnit` 은 이렇게 된다.

```java
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> sumByUnit(UUID tenantId, String source, Instant baseAt,
                                           String compareField, FilterSpec filter) {
        String column = CompareField.sanitize(compareField);
        // 상대 날짜(「오늘+30」)는 이 회차의 기준 시각으로 푼다. 저장 시점에 풀면 저장한 날짜로
        // 굳어, 매일 도는 대조가 다음 날부터 조용히 어긋난다.
        FilterSpec.Compiled where = filter.compile("s", FilterSpec.PREFIX, baseAt);

        List<Map.Entry<UUID, BigDecimal>> rows = jdbcClient.sql("""
                        SELECT m.unit_id AS unit_id, coalesce(sum(s.%s * m.factor), 0) AS qty
                          FROM std_stock_snapshot s
                          JOIN reconcile_unit_member m
                            ON m.tenant_id = s.tenant_id
                           AND m.source    = s.source
                           AND m.item_ref  = s.item_ref
                           AND m.confirmed_at IS NOT NULL
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = :baseAt%s
                         GROUP BY m.unit_id
                        """.formatted(column, where.sql()))
                .param("tenantId", tenantId)
                .param("source", source)
                .param("baseAt", baseAt.atOffset(ZoneOffset.UTC))
                .params(where.params())
                .query((rs, rowNum) -> Map.entry(
                        rs.getObject("unit_id", UUID.class),
                        rs.getBigDecimal("qty")))
                .list();

        Map<UUID, BigDecimal> sums = new LinkedHashMap<>();
        rows.forEach(entry -> sums.put(entry.getKey(), entry.getValue()));
        return sums;
    }
```

`unmatched` 도 같은 방식으로 바꾼다 — `scope.sqlAnd("s")` → `where.sql()`, `.params(scope.params())` → `.params(where.params())`. 인자 없는 편의 오버로드 두 개는 `FilterSpec.all()` 을 넘기도록 그대로 둔다.

- [ ] **Step 6: `MatchBoard` 를 바꾼다**

`findItem` 의 `WarehouseScope scope` → `FilterSpec filter`, 그리고 `stockLines` 의 좌·우를 **다른 접두어**로 컴파일한다.

```java
    private List<StockLine> stockLines(UUID tenantId, Pairing pairing) {
        // 원천마다 볼 조건이 다르다. 한 이름으로 걸면 뒤엣값이 앞을 덮어써 양쪽이 같은 조건으로
        // 걸리므로, 좌·우를 다른 접두어로 바인딩한다.
        Instant asOf = Instant.now();
        FilterSpec.Compiled left = pairing.leftFilter().compile("s", "lf", asOf);
        FilterSpec.Compiled right = pairing.rightFilter().compile("s", "rf", asOf);

        return jdbcClient.sql("""
                        SELECT s.source                            AS source,
                               s.item_ref                          AS item_ref,
                               max(coalesce(s.raw_item_name, ''))  AS raw_name,
                               sum(s.base_quantity)                AS qty
                          FROM std_stock_snapshot s
                         WHERE s.tenant_id = :tenantId
                           AND (    (s.source = :leftSource%s)
                                 OR (s.source = :rightSource%s) )
                           AND s.base_at   = (SELECT max(x.base_at) FROM std_stock_snapshot x
                                               WHERE x.tenant_id = s.tenant_id
                                                 AND x.source    = s.source)
                         GROUP BY s.source, s.item_ref
                        """.formatted(left.sql(), right.sql()))
                .param("tenantId", tenantId)
                .param("leftSource", pairing.leftSource())
                .param("rightSource", pairing.rightSource())
                .params(left.params())
                .params(right.params())
                .query((rs, rowNum) -> new StockLine(
                        rs.getString("source"), rs.getString("item_ref"),
                        rs.getString("raw_name"), rs.getBigDecimal("qty")))
                .list();
    }
```

> `Instant.now()` 를 쓰는 이유 — 이 조회는 「가장 최근 자료」 를 보므로 회차 기준 시각이 없다.
> 상대 날짜는 「지금」 을 기준으로 푸는 것이 이 화면의 뜻과 맞는다.

- [ ] **Step 7: `ReconcileEngine` 의 호출부를 바꾼다**

`ReconcileEngine` 에 `FilterService filters` 를 주입하고, `definition.leftScope()` → `filters.specOf(definition.getId(), FilterSide.LEFT)`, `definition.rightScope()` → `…RIGHT` 로 바꾼다. `run.recordScope(Pairing.of(definition))` 은 `run.recordScope(filters.pairingOf(definition))` 이 된다. `addUnmatched` 의 삼항도 `pairing.filterOf(source)` 로 바꾸어 **조건을 고르는 판단을 한 곳으로 모은다**.

- [ ] **Step 8: `ReconcileDefinition` 에서 창고를 걷어낸다**

`leftWarehouses`·`rightWarehouses` 필드와 `leftScope()`·`rightScope()`·`changeWarehouses()` 를 지운다. **컬럼은 DB 에 남지만 엔티티가 읽지 않는다** — Hibernate 는 매핑되지 않은 컬럼을 무시한다.

- [ ] **Step 9: `WarehouseScope.java` 를 지우고 호출부를 고친다**

```bash
git rm palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/define/WarehouseScope.java
grep -rn "WarehouseScope" --include=*.java . | grep -v "/build/"
```
남는 참조가 없을 때까지 고친다. `ReconcileEngineIntegrationTest` 의 창고 시험은 `FilterSpec` 으로 다시 쓴다.

- [ ] **Step 10: 시험 전체를 돌린다**

Run: `./gradlew :palim-reconcile:test :palim-app:test`
Expected: 전부 PASS. 특히 `ReconcileEngineIntegrationTest`·`MatchBoardIntegrationTest`·`UnitBreakdownIntegrationTest`·`SnapshotAggregatorIntegrationTest` 가 통과해야 한다 — 이 넷이 「네 쿼리가 같은 숫자를 낸다」 를 지키는 시험이다.

- [ ] **Step 11: 커밋**

```bash
git add -A
git commit -m "창고 말고 다른 조건으로는 대조를 좁힐 수가 없다 : refactor : 창고 전용 범위를 걷고 네 조회가 같은 조건을 보게 함 https://github.com/Cassiiopeia/palim/issues/161"
```

---

### Task 6: 회차가 그때 쓴 조건을 남긴다

회차는 편집 대상이 아니라 **기록**이다. 지금 회차 상세를 열면 「오늘의 정의」 로 다시 계산되는데, 조건이 늘어난 뒤로는 조건을 바꾸는 순간 **지난 회차의 저장된 합계와 화면의 상세가 어긋난다.** 게다가 회차마다 맞기도 하고 틀리기도 해서 「늘 틀린다」 보다 원인을 찾기 어렵다.

**Files:**
- Create: `palim-app/src/main/resources/db/migration/V35__reconcile_run_filters.sql`
- Modify: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/run/ReconcileRun.java:109-140`
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterSnapshot.java`
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/FilterRunSnapshotIntegrationTest.java`

**Interfaces:**
- Consumes: `FilterSpec`·`FilterNode` (T3), `Pairing` (T5)
- Produces:
  - `record FilterSnapshot(String leftExpression, String rightExpression, String compareField, List<Resolved> resolvedDates)` + `record Resolved(String raw, String value)`
  - `ReconcileRun.recordScope(Pairing pairing, Instant asOf)` — 기존 시그니처를 넓힌다
  - `ReconcileRun.getFilters()` → `FilterSnapshot` (없으면 옛 창고 컬럼에서 만든다)

- [ ] **Step 1: 실패하는 시험을 쓴다**

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.engine.ReconcileEngine;
import kr.suhsaechan.palim.reconcile.filter.FilterOperator;
import kr.suhsaechan.palim.reconcile.filter.FilterRow;
import kr.suhsaechan.palim.reconcile.filter.FilterRowRepository;
import kr.suhsaechan.palim.reconcile.filter.FilterSide;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
import kr.suhsaechan.palim.reconcile.run.ReconcileRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 회차는 <b>자기가 무엇을 견줬는지</b> 를 남긴다.
 *
 * <p>남기지 않으면 지난 회차의 상세를 열 때 「오늘의 정의」 로 다시 계산된다. 조건을 바꾼
 * 순간부터 저장된 합계와 화면의 상세가 어긋나는데, 회차마다 맞기도 하고 틀리기도 해서
 * 「늘 틀린다」 보다 원인을 찾기 어렵다.
 */
class FilterRunSnapshotIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private ReconcileEngine engine;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private ReconcileRunRepository runs;
    @Autowired private FilterRowRepository filterRows;
    @Autowired private JdbcClient jdbcClient;

    private Instant baseAt;
    private String left;
    private String right;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        baseAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        left = "l-" + UUID.randomUUID().toString().substring(0, 6);
        right = "r-" + UUID.randomUUID().toString().substring(0, 6);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("회차에 그때 걸린 조건이 남는다")
    void recordsFilters() {
        ReconcileDefinition definition = definition();
        filterRows.save(FilterRow.field(TENANT, definition.getId(), FilterSide.LEFT, 0,
                "warehouse_code", FilterOperator.IN, List.of("01")));
        snapshot(left, "A", "100", "01");
        snapshot(right, "B", "100", "");

        ReconcileRun run = engine.run(TENANT, definition.getId());

        assertThat(run.getFilters().leftExpression()).contains("창고").contains("01");
        assertThat(run.getFilters().rightExpression()).isEqualTo("전체");
    }

    @Test
    @DisplayName("정의를 바꿔도 지난 회차가 남긴 조건은 그대로다")
    void pastRunKeepsItsFilters() {
        ReconcileDefinition definition = definition();
        filterRows.save(FilterRow.field(TENANT, definition.getId(), FilterSide.LEFT, 0,
                "warehouse_code", FilterOperator.IN, List.of("01")));
        snapshot(left, "A", "100", "01");
        snapshot(right, "B", "100", "");

        ReconcileRun first = engine.run(TENANT, definition.getId());
        String recorded = first.getFilters().leftExpression();

        // 정의를 통째로 바꾼다.
        filterRows.deleteAll(filterRows.findByDefinitionIdOrderBySideAscOrdinalAsc(
                definition.getId()));
        filterRows.save(FilterRow.field(TENANT, definition.getId(), FilterSide.LEFT, 0,
                "quality_status", FilterOperator.EQ, List.of("정상")));

        ReconcileRun reloaded = runs.findById(first.getId()).orElseThrow();

        assertThat(reloaded.getFilters().leftExpression()).isEqualTo(recorded);
        assertThat(reloaded.getFilters().leftExpression()).doesNotContain("품질상태");
    }

    @Test
    @DisplayName("상대 날짜는 푼 값과 원래 표현을 함께 남긴다")
    void recordsResolvedDates() {
        ReconcileDefinition definition = definition();
        filterRows.save(FilterRow.field(TENANT, definition.getId(), FilterSide.LEFT, 0,
                "expiry_date", FilterOperator.GTE, List.of("오늘+30")));
        snapshot(left, "A", "100", "01");
        snapshot(right, "B", "100", "");

        ReconcileRun run = engine.run(TENANT, definition.getId());

        assertThat(run.getFilters().resolvedDates())
                .anySatisfy(resolved -> {
                    assertThat(resolved.raw()).isEqualTo("오늘+30");
                    assertThat(resolved.value()).matches("\\d{4}-\\d{2}-\\d{2}");
                });
    }

    @Test
    @DisplayName("V35 이전 회차는 옛 창고 컬럼으로 읽는다 — 기록이 사라지지 않는다")
    void readsLegacyColumns() {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "100", "01");
        snapshot(right, "B", "100", "");
        ReconcileRun run = engine.run(TENANT, definition.getId());

        // V35 이전 회차를 흉내 낸다 — filters_json 은 비고 옛 컬럼만 있다.
        jdbcClient.sql("""
                        UPDATE reconcile_run
                           SET filters_json = NULL, left_warehouses = '01,02'
                         WHERE id = :id
                        """)
                .param("id", run.getId()).update();

        ReconcileRun reloaded = runs.findById(run.getId()).orElseThrow();

        assertThat(reloaded.getFilters().leftExpression()).contains("01").contains("02");
    }

    private ReconcileDefinition definition() {
        return definitions.save(ReconcileDefinition.of(TENANT,
                "DEF-" + UUID.randomUUID().toString().substring(0, 8), "시험 대조",
                left, right, "base_quantity", BigDecimal.ZERO, null));
    }

    private void snapshot(String source, String itemRef, String qty, String warehouse) {
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             expiry_date, quantity, base_quantity, base_unit, raw_item_name,
                             created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, :warehouse, '',
                                current_date + 60, :qty, :qty, 'EA', :name, :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", baseAt.atOffset(ZoneOffset.UTC))
                .param("source", source)
                .param("warehouse", warehouse)
                .param("qty", new BigDecimal(qty))
                .param("name", "품목 " + itemRef)
                .update();
    }
}
```

- [ ] **Step 2: 시험을 돌려 실패를 확인한다**

Run: `./gradlew :palim-app:test --tests "*FilterRunSnapshotIntegrationTest*"`
Expected: 컴파일 실패 — `run.getFilters()` 없음

- [ ] **Step 3: 마이그레이션 `V35` 를 쓴다**

```sql
-- 회차가 «그때 무엇을 봤는지» 를 조건까지 남긴다.
--
-- V32 가 창고 CSV 로 하던 일을 넓힌다. 조건이 창고 하나에서 여러 칸으로 늘었으므로 CSV 두
-- 칸으로는 담을 수 없다.
--
-- **표로 쪼개지 않는다.** 회차는 편집 대상이 아니라 기록이고, 조회도 「그때 뭐였나」 를 통째로
-- 읽는 것뿐이라 조인만 늘어난다. 그리고 카탈로그에서 사라진 칸도 그대로 남길 수 있다 —
-- 정규화된 표라면 없는 칸을 가리키는 행이 되어 무결성이 애매해진다.
--
-- 비어 있으면 V32 의 옛 컬럼을 읽는다. 그 이전 회차는 「전 창고를 봤다」 로 읽는다.
ALTER TABLE reconcile_run ADD COLUMN filters_json jsonb;

COMMENT ON COLUMN reconcile_run.filters_json IS
    '이 회차가 쓴 조건. 좌·우 식과 푼 상대 날짜. NULL 이면 left_warehouses 를 읽는다';
```

- [ ] **Step 4: `FilterSnapshot` 을 만든다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 한 회차가 <b>무엇을 봤는지</b> 의 기록.
 *
 * <p>푼 값과 원래 표현을 <b>함께</b> 적는다. 「그때 무슨 날짜로 걸렸나」 와 「무슨 규칙이었나」 는
 * 다른 질문이고, 둘 다 필요하다 — 앞의 것 없이는 결과를 재현할 수 없고, 뒤의 것 없이는 왜 그
 * 날짜였는지 알 수 없다.
 *
 * @param leftExpression  좌측에 걸린 조건을 사람이 읽는 글로. 「전체」 이면 안 걸린 것
 * @param rightExpression 우측 조건
 * @param compareField    그때 더한 수치 칸
 * @param resolvedDates   푼 상대 날짜들
 */
public record FilterSnapshot(String leftExpression, String rightExpression,
                             String compareField, List<Resolved> resolvedDates) {

    /** 「전체」 로 읽히는 말. 조건이 없었다는 뜻이다. */
    public static final String ALL = "전체";

    public FilterSnapshot {
        resolvedDates = resolvedDates == null ? List.of() : List.copyOf(resolvedDates);
    }

    /** 상대 날짜 하나가 그때 무엇으로 풀렸는가. */
    public record Resolved(String raw, String value) {
    }

    /** 지금 조건에서 기록을 만든다. */
    public static FilterSnapshot of(FilterSpec left, FilterSpec right,
                                    String compareField, Instant asOf) {
        List<Resolved> resolved = new ArrayList<>();
        collectDates(left.root(), asOf, resolved);
        collectDates(right.root(), asOf, resolved);
        return new FilterSnapshot(left.describe(), right.describe(), compareField, resolved);
    }

    /** V35 이전 회차. 옛 창고 CSV 를 읽는다. */
    public static FilterSnapshot fromLegacy(String leftWarehouses, String rightWarehouses,
                                            String compareField) {
        return new FilterSnapshot(describeLegacy(leftWarehouses),
                describeLegacy(rightWarehouses), compareField, List.of());
    }

    private static String describeLegacy(String csv) {
        return csv == null || csv.isBlank() ? ALL : "창고 이것만 " + csv.replace(",", ", ");
    }

    private static void collectDates(FilterNode node, Instant asOf, List<Resolved> into) {
        switch (node) {
            case FilterNode.Compare compare -> {
                if (compare.field().type() != FieldType.DATE) {
                    return;
                }
                for (String raw : compare.values()) {
                    DateToken.parse(raw).ifPresent(token ->
                            into.add(new Resolved(raw, token.resolve(asOf).toString())));
                }
            }
            case FilterNode.And and -> and.children().forEach(c -> collectDates(c, asOf, into));
            case FilterNode.Or or -> or.children().forEach(c -> collectDates(c, asOf, into));
            case FilterNode.Not not -> collectDates(not.child(), asOf, into);
            case FilterNode.All ignored -> {
                // 남길 것이 없다.
            }
        }
    }
}
```

- [ ] **Step 5: `ReconcileRun` 을 바꾼다**

`filtersJson` 컬럼을 더하고 `recordScope` 를 넓힌다. 옛 컬럼(`leftWarehouses`·`rightWarehouses`)은 **읽기만** 남긴다.

```java
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filters_json", columnDefinition = "jsonb")
    private FilterSnapshot filters;

    /**
     * 이 회차가 무엇을 견줬는지 남긴다.
     *
     * <p>상대 날짜는 <b>여기서 푼다.</b> 회차가 도는 시각이 그 회차의 「오늘」 이기 때문이다.
     */
    public void recordScope(Pairing pairing, Instant asOf) {
        this.filters = FilterSnapshot.of(pairing.leftFilter(), pairing.rightFilter(),
                pairing.compareField(), asOf);
        this.compareField = pairing.compareField();
    }

    /**
     * 이 회차가 쓴 조건.
     *
     * <p>V35 이전 회차는 {@code filters_json} 이 비어 있다 — 그때의 기록인 옛 창고 칸을 읽는다.
     * 기록이 사라지면 「그 회차는 무엇을 봤나」 에 답할 방법이 없어진다.
     */
    public FilterSnapshot getFilters() {
        return filters != null ? filters
                : FilterSnapshot.fromLegacy(leftWarehouses, rightWarehouses, compareField);
    }
```

`scopeOf(String, String)` 는 **삭제한다** — 회차에서 `Pairing` 을 되살려 다시 계산하던 길인데, 이제 회차는 「무엇을 봤는지」 를 글로만 남긴다. 지난 회차 화면은 저장된 차이를 그대로 보여주지 다시 계산하지 않는다. 호출부(`ReconcileController.runDetail`)를 그에 맞게 고친다.

- [ ] **Step 6: `ReconcileEngine` 의 호출을 고친다**

```java
        run.recordScope(filters.pairingOf(definition), baseAt);
```

- [ ] **Step 7: 시험을 돌려 통과를 확인한다**

Run: `./gradlew :palim-app:test --tests "*FilterRunSnapshotIntegrationTest*" --tests "*Reconcile*"`
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "창고 말고 다른 조건으로는 대조를 좁힐 수가 없다 : feat : 회차가 그때 쓴 조건과 푼 날짜를 남겨 지난 결과가 그대로 재현되게 함 https://github.com/Cassiiopeia/palim/issues/161"
```

---

### Task 7: 식 파서와 되쓰기

줄로 안 되는 조건 — 칸을 넘는 OR, 괄호 — 을 글로 쓴다. **검사하지 않고 다시 만든다:** 사용자 글은 AST 가 되고, SQL 은 Task 3 의 노드가 적어 둔 틀에서만 나온다.

금지어 목록으로 막지 않는다. 그것은 막을 것을 전부 알고 있어야 성립하는 방어라 언젠가 뚫린다. 대신 **통과한 것으로 무엇을 만들지**를 우리가 정한다.

**Files:**
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/ExpressionParser.java`
- Create: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/ExpressionWriter.java`
- Modify: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterSpec.java` — `describe()` 의 임시 구현을 `ExpressionWriter.write(root)` 로
- Modify: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/filter/FilterCompiler.java` — `EXPRESSION` 줄을 파싱해 AND 로 함께 건다
- Modify: `palim-common/.../ErrorCode.java` + `errors.properties` + `errors_en.properties`
- Test: `palim-reconcile/src/test/java/kr/suhsaechan/palim/reconcile/filter/ExpressionParserTest.java`
- Test: `palim-reconcile/src/test/java/kr/suhsaechan/palim/reconcile/filter/ExpressionInjectionTest.java`
- Test: `palim-reconcile/src/test/java/kr/suhsaechan/palim/reconcile/filter/ExpressionWriterTest.java`

**Interfaces:**
- Consumes: `FieldCatalog`·`FilterableField` (T1), `FilterOperator`·`DateToken` (T2), `FilterNode` (T3)
- Produces:
  - `final class ExpressionParser` — `static FilterNode parse(String text)` (못 읽으면 `BusinessException`), `static int MAX_LENGTH = 2000`, `MAX_DEPTH = 20`, `MAX_NODES = 200`
  - `final class ExpressionWriter` — `static String write(FilterNode node)`

- [ ] **Step 1: 정상 식 시험을 쓴다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 줄로 안 되는 조건을 글로 쓴다.
 *
 * <p>한글 연산자를 함께 받는 이유는 이 화면을 쓰는 사람이 개발자가 아니기 때문이다. 「그리고」
 * 로 쓰든 {@code AND} 로 쓰든 같은 나무가 된다.
 */
class ExpressionParserTest {

    private static final Instant AS_OF = Instant.parse("2026-08-22T03:00:00Z");

    private static String sql(String text) {
        return new FilterSpec(ExpressionParser.parse(text)).sqlAnd("s", "f", AS_OF);
    }

    @Test
    @DisplayName("칸을 넘는 OR 을 괄호로 묶는다 — 줄로는 쓸 수 없던 조건이다")
    void parsesOrAcrossFields() {
        assertThat(sql("(창고 = '01' 또는 창고 = '02') 그리고 품질상태 ≠ '불량'"))
                .isEqualTo(" AND ((s.warehouse_code = :f0 OR s.warehouse_code = :f1)"
                        + " AND s.quality_status <> :f2)");
    }

    @Test
    @DisplayName("영문 연산자도 같은 나무가 된다")
    void acceptsEnglishOperators() {
        assertThat(sql("warehouse_code = '01' AND quality_status = '정상'"))
                .isEqualTo(sql("창고 = '01' 그리고 품질상태 = '정상'"));
    }

    @Test
    @DisplayName("IN · BETWEEN · 비었음을 읽는다")
    void parsesMultiValueOperators() {
        assertThat(sql("창고 IN ('01', '02')"))
                .isEqualTo(" AND (s.warehouse_code IN (:f0, :f1))");
        assertThat(sql("유통기한 사이 오늘 AND 오늘+30"))
                .isEqualTo(" AND (s.expiry_date BETWEEN :f0 AND :f1)");
        assertThat(sql("로트 비었음"))
                .isEqualTo(" AND (coalesce(s.lot_code, '') = '')");
    }

    @Test
    @DisplayName("아님으로 통째로 뒤집는다")
    void parsesNot() {
        assertThat(sql("아님 (창고 = '01')"))
                .isEqualTo(" AND (NOT (s.warehouse_code = :f0))");
    }

    @Test
    @DisplayName("원천 고유 칸도 식에서 걸 수 있다")
    void parsesAttributeField() {
        assertThat(sql("attributes.재고구분 = '정상'"))
                .isEqualTo(" AND (s.attributes->>'재고구분' = :f0)");
    }

    @Test
    @DisplayName("AND 가 OR 보다 세게 묶인다 — 괄호 없이 쓴 뜻이 상식과 맞아야 한다")
    void andBindsTighterThanOr() {
        assertThat(sql("창고 = '01' 또는 창고 = '02' 그리고 품질상태 = '정상'"))
                .isEqualTo(" AND (s.warehouse_code = :f0"
                        + " OR (s.warehouse_code = :f1 AND s.quality_status = :f2))");
    }

    @Test
    @DisplayName("빈 글은 아무것도 거르지 않는다")
    void blankMeansAll() {
        assertThat(ExpressionParser.parse("").isAll()).isTrue();
        assertThat(ExpressionParser.parse("   ").isAll()).isTrue();
        assertThat(ExpressionParser.parse(null).isAll()).isTrue();
    }

    @Test
    @DisplayName("칸에 맞지 않는 연산자는 거부한다 — 글 칸에 「사이」 는 쓸 수 없다")
    void rejectsOperatorTypeMismatch() {
        assertThatBusiness(() -> ExpressionParser.parse("창고 사이 1 AND 2"));
    }

    @Test
    @DisplayName("연산자가 요구하는 값 개수를 어기면 거부한다")
    void rejectsWrongValueCount() {
        assertThatBusiness(() -> ExpressionParser.parse("유통기한 사이 오늘"));
        assertThatBusiness(() -> ExpressionParser.parse("창고 IN ()"));
    }

    @Test
    @DisplayName("읽을 수 없는 날짜는 거부한다 — 도는 순간까지 미루지 않는다")
    void rejectsBadDate() {
        assertThatBusiness(() -> ExpressionParser.parse("유통기한 이후 '어제'"));
    }

    private static void assertThatBusiness(org.assertj.core.api.ThrowableAssert
            .ThrowingCallable callable) {
        org.assertj.core.api.Assertions.assertThatThrownBy(callable)
                .isInstanceOf(kr.suhsaechan.palim.common.error.BusinessException.class);
    }
}
```

- [ ] **Step 2: 인젝션 시험을 쓴다 — 이 파일이 안전의 증거다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.suhsaechan.palim.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 위험한 것은 <b>막는 게 아니라 문법에 없다.</b>
 *
 * <p>금지어 목록으로 막는 방식은 막을 것을 전부 알고 있어야 성립하고, 그래서 언젠가 뚫린다.
 * 여기 적힌 것들이 실패하는 이유는 「위험해서 걸렀다」 가 아니라 <b>문법이 그것을 표현하지
 * 못해서</b> 다 — 함수 호출·서브쿼리·세미콜론·주석이 규칙에 아예 없다.
 *
 * <p>설령 파서에 구멍이 있어 무엇이 통과해도, 통과한 것은 {@code FilterNode} 가 되고 그
 * 노드가 만들 수 있는 SQL 은 {@code FilterNode.Compare#appendTo} 에 적힌 틀뿐이다.
 */
class ExpressionInjectionTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "창고 = '01'; DROP TABLE std_stock_snapshot",
            "창고 = '01' -- 나머지는 주석",
            "창고 = '01' /* 주석 */ 또는 1=1",
            "창고 = '01' UNION SELECT 1",
            "창고 = (SELECT max(id) FROM std_stock_snapshot)",
            "lower(창고) = '01'",
            "pg_sleep(10) = 1",
            "tenant_id = '00000000-0000-0000-0000-000000000000'",
            "1 = 1",
            "창고 = '01' 또는 '1'='1'",
            "창고 = '01'' OR ''1''=''1'",
            "\"warehouse_code\" = '01'",
            "s.warehouse_code = '01'",
            "std_stock_snapshot.warehouse_code = '01'",
            "창고 = 01",
            "COPY std_stock_snapshot TO '/tmp/x'",
            "창고 = '01' \\g",
    })
    @DisplayName("읽지 못한다 — 문법에 그런 것이 없기 때문이다")
    void refusesToRead(String payload) {
        assertThatThrownBy(() -> ExpressionParser.parse(payload))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "'); DROP TABLE std_stock_snapshot; --",
            "' OR '1'='1",
            "\\'; DELETE FROM reconcile_run; --",
    })
    @DisplayName("값 자리에 든 주입 시도는 값일 뿐이다 — 바인딩되어 SQL 이 되지 않는다")
    void payloadInValuePositionStaysAValue(String payload) {
        String text = "창고 = '" + payload.replace("'", "''") + "'";
        FilterNode node = ExpressionParser.parse(text);

        String sql = new FilterSpec(node)
                .sqlAnd("s", "f", java.time.Instant.parse("2026-08-22T03:00:00Z"));

        org.assertj.core.api.Assertions.assertThat(sql)
                .isEqualTo(" AND (s.warehouse_code = :f0)")
                .doesNotContain("DROP").doesNotContain("DELETE").doesNotContain("--");
    }

    @Test
    @DisplayName("길이·깊이·노드 수 상한을 넘으면 거부한다 — 파싱은 되어도 비싼 것은 만들 수 있다")
    void refusesOversized() {
        assertThatThrownBy(() -> ExpressionParser.parse("창고 = '01' 그리고 ".repeat(400)
                + "창고 = '01'"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("(".repeat(50)
                + "창고 = '01'" + ")".repeat(50)))
                .isInstanceOf(BusinessException.class);
    }
}
```

> `@Test` 를 함께 쓰므로 `import org.junit.jupiter.api.Test;` 를 넣는다.

- [ ] **Step 3: 되쓰기 시험을 쓴다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 나무를 글로 되돌린다.
 *
 * <p>조건 줄과 식이 한 나무로 모이므로 <b>「지금 조건을 식으로 보기」 가 공짜로 나온다.</b>
 * 사람이 조건 줄로 시작해 식을 배우는 길이 되고, 「내가 고른 것이 무슨 뜻인지」 를 확인하는
 * 길도 된다.
 */
class ExpressionWriterTest {

    private static FilterNode.Compare compare(String key, FilterOperator op, String... values) {
        return new FilterNode.Compare(FieldCatalog.find(key).orElseThrow(), op, List.of(values));
    }

    @Test
    @DisplayName("조건 줄을 글로 되돌린다")
    void writesRows() {
        FilterNode node = new FilterNode.And(List.of(
                compare("warehouse_code", FilterOperator.IN, "01", "02"),
                compare("quality_status", FilterOperator.NOT_IN, "불량")));

        assertThat(ExpressionWriter.write(node))
                .isEqualTo("창고 이것만 ('01', '02') 그리고 품질상태 이것 빼고 ('불량')");
    }

    @Test
    @DisplayName("되돌린 글을 다시 읽으면 같은 SQL 이 나온다 — 두 입구가 한 나무로 모인다")
    void roundTrips() {
        FilterNode original = new FilterNode.And(List.of(
                new FilterNode.Or(List.of(
                        compare("warehouse_code", FilterOperator.EQ, "01"),
                        compare("warehouse_code", FilterOperator.EQ, "02"))),
                compare("expiry_date", FilterOperator.GTE, "오늘+30")));

        FilterNode reparsed = ExpressionParser.parse(ExpressionWriter.write(original));

        java.time.Instant asOf = java.time.Instant.parse("2026-08-22T03:00:00Z");
        assertThat(new FilterSpec(reparsed).compile("s", "f", asOf))
                .isEqualTo(new FilterSpec(original).compile("s", "f", asOf));
    }

    @Test
    @DisplayName("조건이 없으면 「전체」 라고 말한다")
    void writesAll() {
        assertThat(ExpressionWriter.write(FilterNode.ALL)).isEqualTo("전체");
    }

    @Test
    @DisplayName("값 안의 작은따옴표는 두 번 적어 되읽을 수 있게 한다")
    void escapesQuotes() {
        String written = ExpressionWriter.write(
                compare("raw_item_name", FilterOperator.CONTAINS, "a'b"));

        assertThat(written).isEqualTo("원본 품명 포함 'a''b'");
        assertThat(ExpressionParser.parse(written)).isEqualTo(
                compare("raw_item_name", FilterOperator.CONTAINS, "a'b"));
    }
}
```

- [ ] **Step 4: 세 시험을 돌려 실패를 확인한다**

Run: `./gradlew :palim-reconcile:test --tests "*Expression*"`
Expected: 컴파일 실패 — `ExpressionParser`·`ExpressionWriter` 없음

- [ ] **Step 5: `ErrorCode` 두 줄과 메시지를 더한다**

```java
    /** 식을 읽지 못했다. 어디서 막혔는지를 함께 알려 도는 순간까지 미루지 않는다. */
    FILTER_EXPRESSION_INVALID("R014", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.DEBUG),

    /**
     * 식이 너무 길거나 깊다.
     *
     * <p>파싱이 되어도 비싼 것은 만들 수 있다. 상한이 없으면 화면의 미리보기 하나가 서버를
     * 물고 늘어진다.
     */
    FILTER_EXPRESSION_TOO_COMPLEX("R015", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.WARN),
```

`errors.properties`:
```properties
error.FILTER_EXPRESSION_INVALID=식을 읽지 못했습니다 — {0}
error.FILTER_EXPRESSION_TOO_COMPLEX=식이 너무 복잡합니다. {0}
```

`errors_en.properties`:
```properties
error.FILTER_EXPRESSION_INVALID=Could not read the expression - {0}
error.FILTER_EXPRESSION_TOO_COMPLEX=The expression is too complex. {0}
```

- [ ] **Step 6: `ExpressionParser` 를 만든다**

문법은 스펙의 것을 그대로 따른다.

```
   or         := and ( ("또는" | "OR") and )*
   and        := not ( ("그리고" | "AND") not )*
   not        := ("아님" | "NOT")? primary
   primary    := "(" or ")" | comparison
   comparison := field operator operand*
   field      := 카탈로그 키 또는 그 화면 이름   ← 없으면 읽기 실패
   operand    := '문자열' | 숫자 | 날짜토큰
```

```java
package kr.suhsaechan.palim.reconcile.filter;

import java.util.ArrayList;
import java.util.List;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;

/**
 * 식을 나무로 읽는다.
 *
 * <p><b>식별자 자리에 올 수 있는 것은 카탈로그 키뿐이다.</b> 문법에 임의 식별자가 없다 —
 * 그래서 표 이름·다른 칼럼·함수 이름을 적을 방법이 없다. 함수 호출·서브쿼리·세미콜론·주석도
 * 마찬가지로 문법에 없다. <b>표현할 수단이 없는 것은 막을 필요도 없다.</b>
 *
 * <p>실패는 <b>저장 시점에 드러난다.</b> 못 읽는 식은 저장이 거부되고 어디서 막혔는지를 화면이
 * 가리킨다 — 도는 순간까지 미뤄지지 않는다.
 */
public final class ExpressionParser {

    /** 글 길이 상한. 파싱이 되어도 비싼 것은 만들 수 있다. */
    public static final int MAX_LENGTH = 2000;
    /** 나무 깊이 상한. */
    public static final int MAX_DEPTH = 20;
    /** 노드 수 상한. */
    public static final int MAX_NODES = 200;

    private final List<String> tokens;
    private int pos;

    private ExpressionParser(List<String> tokens) {
        this.tokens = tokens;
    }

    /** 못 읽으면 {@link BusinessException} 을 던진다. 비어 있으면 「전부」. */
    public static FilterNode parse(String text) {
        if (text == null || text.isBlank()) {
            return FilterNode.ALL;
        }
        if (text.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.FILTER_EXPRESSION_TOO_COMPLEX,
                    "글이 %d자를 넘었습니다(상한 %d자)".formatted(text.length(), MAX_LENGTH));
        }
        ExpressionParser parser = new ExpressionParser(tokenize(text));
        FilterNode node = parser.or();
        if (parser.pos < parser.tokens.size()) {
            throw invalid("«%s» 부터는 읽을 수 없습니다".formatted(parser.tokens.get(parser.pos)));
        }
        if (node.depth() > MAX_DEPTH) {
            throw new BusinessException(ErrorCode.FILTER_EXPRESSION_TOO_COMPLEX,
                    "괄호가 %d겹을 넘었습니다(상한 %d겹)".formatted(node.depth(), MAX_DEPTH));
        }
        if (node.nodeCount() > MAX_NODES) {
            throw new BusinessException(ErrorCode.FILTER_EXPRESSION_TOO_COMPLEX,
                    "조건이 %d개를 넘었습니다(상한 %d개)".formatted(node.nodeCount(), MAX_NODES));
        }
        return node;
    }

    // ===== 문법 =====

    private FilterNode or() {
        List<FilterNode> parts = new ArrayList<>();
        parts.add(and());
        while (matchesAny("또는", "OR")) {
            pos++;
            parts.add(and());
        }
        return parts.size() == 1 ? parts.get(0) : new FilterNode.Or(parts);
    }

    private FilterNode and() {
        List<FilterNode> parts = new ArrayList<>();
        parts.add(not());
        while (matchesAny("그리고", "AND")) {
            pos++;
            parts.add(not());
        }
        return parts.size() == 1 ? parts.get(0) : new FilterNode.And(parts);
    }

    private FilterNode not() {
        if (matchesAny("아님", "NOT")) {
            pos++;
            return new FilterNode.Not(primary());
        }
        return primary();
    }

    private FilterNode primary() {
        if (matches("(")) {
            pos++;
            FilterNode inner = or();
            expect(")");
            return inner;
        }
        return comparison();
    }

    private FilterNode.Compare comparison() {
        String fieldToken = take("칸 이름이 있어야 합니다");
        FilterableField field = resolveField(fieldToken);
        String opToken = take("«%s» 뒤에 연산자가 있어야 합니다".formatted(fieldToken));
        FilterOperator operator = FilterOperator.ofSymbol(opToken)
                .orElseThrow(() -> invalid("«%s» 는 아는 연산자가 아닙니다".formatted(opToken)));

        if (!operator.supports(field.type())) {
            throw new BusinessException(ErrorCode.FILTER_OPERATOR_MISMATCH,
                    operator.label(), field.label());
        }

        List<String> values = readValues(operator);
        if (!operator.acceptsCount(values.size())) {
            throw new BusinessException(ErrorCode.FILTER_VALUE_COUNT,
                    operator.label(), values.size());
        }
        validateValues(field, operator, values);
        return new FilterNode.Compare(field, operator, values);
    }

    private List<String> readValues(FilterOperator operator) {
        List<String> values = new ArrayList<>();
        switch (operator.arity()) {
            case NONE -> {
                // 읽을 값이 없다.
            }
            case ONE -> values.add(operand());
            case TWO -> {
                values.add(operand());
                if (!matchesAny("그리고", "AND")) {
                    throw invalid("«사이» 는 값 두 개를 «그리고» 로 이어야 합니다");
                }
                pos++;
                values.add(operand());
            }
            case AT_LEAST_ONE -> {
                expect("(");
                while (!matches(")")) {
                    values.add(operand());
                    if (matches(",")) {
                        pos++;
                    } else {
                        break;
                    }
                }
                expect(")");
            }
        }
        return values;
    }

    /** 값 하나. <b>문자열은 반드시 작은따옴표로 감싼다</b> — 그래야 칸 이름과 헷갈리지 않는다. */
    private String operand() {
        String token = take("값이 있어야 합니다");
        if (token.length() >= 2 && token.charAt(0) == '\''
                && token.charAt(token.length() - 1) == '\'') {
            return token.substring(1, token.length() - 1).replace("''", "'");
        }
        // 따옴표가 없으면 숫자나 날짜 토큰이어야 한다. 그 밖의 맨 낱말은 칸 이름으로 오해되므로
        // 여기서 막는다 — 「창고 = 01」 을 허용하면 「창고 = 창고」 도 읽히게 된다.
        if (isNumber(token) || DateToken.parse(token).isPresent()) {
            return token;
        }
        throw invalid("«%s» 는 값이 아닙니다. 글은 작은따옴표로 감싸 주세요".formatted(token));
    }

    private void validateValues(FilterableField field, FilterOperator operator,
                                List<String> values) {
        for (String value : values) {
            switch (field.type()) {
                case NUMBER -> {
                    if (!isNumber(value)) {
                        throw invalid("«%s» 는 숫자가 아닙니다".formatted(value));
                    }
                }
                case DATE -> DateToken.parse(value).orElseThrow(() ->
                        invalid("«%s» 는 날짜가 아닙니다. «오늘» · «오늘+30» · «2026-08-22» "
                                .formatted(value)));
                case TEXT, BOOL -> {
                    // 글은 무엇이든 값이다. 바인딩되므로 SQL 이 되지 않는다.
                }
            }
        }
        if (operator == FilterOperator.MATCHES) {
            // 폭주하는 정규식을 막는 장치는 정규화 규칙에서 쓰던 RegexGuard 가 이미 있다.
            // 새로 만들지 않는다 — 두 벌이 되면 한쪽만 고쳐지는 날이 온다.
            RegexGuard.validate(values.get(0));
        }
    }

    private FilterableField resolveField(String token) {
        return FieldCatalog.find(token)
                .or(() -> FieldCatalog.standard().stream()
                        .filter(f -> f.label().equals(token))
                        .findFirst())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.FILTER_FIELD_UNKNOWN, token));
    }

    // ===== 토큰 =====

    /**
     * 글을 낱말로 자른다.
     *
     * <p>{@code '…'} 안은 통째로 한 낱말이다. {@code ''} 는 따옴표 한 글자를 뜻한다 —
     * SQL 과 같은 규칙이라 사람이 새로 배울 것이 없다.
     */
    private static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(' || c == ')' || c == ',') {
                out.add(String.valueOf(c));
                i++;
            } else if (c == '\'') {
                int j = i + 1;
                StringBuilder sb = new StringBuilder("'");
                while (j < text.length()) {
                    if (text.charAt(j) == '\'') {
                        if (j + 1 < text.length() && text.charAt(j + 1) == '\'') {
                            sb.append("''");
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    sb.append(text.charAt(j));
                    j++;
                }
                if (j >= text.length()) {
                    throw invalid("따옴표가 닫히지 않았습니다");
                }
                out.add(sb.append('\'').toString());
                i = j + 1;
            } else if (isSymbolStart(c)) {
                int j = i;
                while (j < text.length() && isSymbolStart(text.charAt(j))) {
                    j++;
                }
                out.add(text.substring(i, j));
                i = j;
            } else {
                int j = i;
                while (j < text.length() && !Character.isWhitespace(text.charAt(j))
                        && "(),'".indexOf(text.charAt(j)) < 0
                        && !isSymbolStart(text.charAt(j))) {
                    j++;
                }
                out.add(text.substring(i, j));
                i = j;
            }
        }
        return out;
    }

    private static boolean isSymbolStart(char c) {
        return "=<>!≠≥≤".indexOf(c) >= 0;
    }

    private boolean matches(String token) {
        return pos < tokens.size() && tokens.get(pos).equals(token);
    }

    private boolean matchesAny(String... candidates) {
        if (pos >= tokens.size()) {
            return false;
        }
        String token = tokens.get(pos);
        for (String candidate : candidates) {
            if (token.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void expect(String token) {
        if (!matches(token)) {
            throw invalid("«%s» 가 있어야 합니다".formatted(token));
        }
        pos++;
    }

    private String take(String message) {
        if (pos >= tokens.size()) {
            throw invalid(message);
        }
        return tokens.get(pos++);
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(ErrorCode.FILTER_EXPRESSION_INVALID, detail);
    }

    private static boolean isNumber(String token) {
        try {
            new java.math.BigDecimal(token);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
```

> `RegexGuard` 는 `palim-reconcile/rule/RegexGuard` 에 **이미 있다**(정규화 규칙에서 쓴다).
> 구현할 때 그 파일을 열어 실제 메서드 이름과 던지는 예외를 확인해 맞춘다 — 이름이 `validate`
> 가 아닐 수 있다. **새 타입을 만들지 않는다.** 두 벌이 되면 한쪽만 고쳐지는 날이 온다.

- [ ] **Step 7: `ExpressionWriter` 를 만든다**

```java
package kr.suhsaechan.palim.reconcile.filter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 나무를 사람이 읽는 글로 되돌린다.
 *
 * <p>조건 줄과 식이 한 나무로 모이므로 이것이 <b>공짜로</b> 나온다. 「지금 조건을 식으로 보기」
 * 가 그 결과이고, 사람이 조건 줄로 시작해 식을 배우는 길이 된다.
 *
 * <p><b>되읽을 수 있는 글을 쓴다.</b> {@link ExpressionParser} 가 다시 읽어 같은 나무가 되어야
 * 한다 — 그러지 않으면 「식으로 보기」 를 눌러 나온 글을 저장할 수 없다.
 */
public final class ExpressionWriter {

    private ExpressionWriter() {
    }

    public static String write(FilterNode node) {
        return switch (node) {
            case FilterNode.All ignored -> "전체";
            case FilterNode.And and -> join(and.children(), " 그리고 ");
            case FilterNode.Or or -> join(or.children(), " 또는 ");
            case FilterNode.Not not -> "아님 (" + write(not.child()) + ")";
            case FilterNode.Compare compare -> writeCompare(compare);
        };
    }

    private static String join(List<FilterNode> children, String glue) {
        return children.stream()
                .map(child -> child instanceof FilterNode.Compare
                        ? write(child) : "(" + write(child) + ")")
                .collect(Collectors.joining(glue));
    }

    private static String writeCompare(FilterNode.Compare compare) {
        String head = "%s %s".formatted(compare.field().label(), compare.operator().symbol());
        return switch (compare.operator().arity()) {
            case NONE -> head;
            case ONE -> head + " " + literal(compare, 0);
            case TWO -> head + " " + literal(compare, 0) + " 그리고 " + literal(compare, 1);
            case AT_LEAST_ONE -> head + " (" + compare.values().stream()
                    .map(value -> quote(compare, value))
                    .collect(Collectors.joining(", ")) + ")";
        };
    }

    private static String literal(FilterNode.Compare compare, int index) {
        return quote(compare, compare.values().get(index));
    }

    /**
     * 값을 글로 적는다.
     *
     * <p>날짜·숫자는 따옴표 없이 적는다 — {@code 오늘+30} 을 {@code '오늘+30'} 으로 적으면
     * 되읽을 때 글자로 취급되어 상대 날짜가 죽는다.
     */
    private static String quote(FilterNode.Compare compare, String value) {
        if (compare.field().type() == FieldType.NUMBER
                || compare.field().type() == FieldType.DATE) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
```

- [ ] **Step 8: `FilterSpec.describe()` 와 `FilterCompiler` 를 잇는다**

`FilterSpec.describe()` 의 임시 구현을 `ExpressionWriter.write(root)` 로 바꾼다.

`FilterCompiler.compile` 의 `continue` 를 실제 파싱으로 바꾼다.

```java
            if (row.isExpression()) {
                FilterNode parsed = ExpressionParser.parse(row.getExpression());
                if (!parsed.isAll()) {
                    nodes.add(parsed);
                }
                continue;
            }
```

식과 조건 줄은 **AND 로 함께** 걸린다 — 이미 `nodes` 를 `And` 로 묶으므로 따로 할 일이 없다.

- [ ] **Step 9: 시험을 돌려 통과를 확인한다**

Run: `./gradlew :palim-reconcile:test --tests "*Expression*" --tests "*FilterSpecTest*"`
Expected: PASS. 인젝션 시험 20건이 전부 「읽지 못함」 으로 떨어져야 한다.

- [ ] **Step 10: 커밋**

```bash
git add -A
git commit -m "창고 말고 다른 조건으로는 대조를 좁힐 수가 없다 : feat : 식을 파싱해 SQL 을 다시 만들어 값이 SQL 이 될 길을 없애고 나무를 글로 되돌림 https://github.com/Cassiiopeia/palim/issues/161"
```

---

### Task 8: 값 후보와 미리보기

화면이 **담긴 자료에 실제로 있는 값**을 보여줘야 사람이 무엇을 골라야 할지 안다. 그리고 **저장 전에 몇 줄이 남는지** 보여야 저장하고 대조를 돌려 봐야 아는 일이 없어진다.

기존 `warehouses()` 를 「아무 칸이나」 로 넓힌다 — 창고 전용 조회를 남겨 두면 로트를 고르려 할 때 같은 것을 또 만들게 된다.

**Files:**
- Modify: `palim-reconcile/src/main/java/kr/suhsaechan/palim/reconcile/engine/SnapshotAggregator.java` — `warehouses()` 를 `valuesOf()` 로 넓히고 `attributeKeys()`·`preview()` 를 더한다
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/FilterOptionsIntegrationTest.java`

**Interfaces:**
- Consumes: `FilterableField`·`FieldCatalog` (T1), `FilterSpec` (T3)
- Produces:
  - `record SnapshotAggregator.FieldValue(String value, String label, int items, BigDecimal qty)`
  - `List<FieldValue> valuesOf(UUID tenantId, String source, FilterableField field)`
  - `List<String> attributeKeys(UUID tenantId, String source)`
  - `record SnapshotAggregator.Preview(int totalItems, int keptItems, BigDecimal keptQty)`
  - `Preview preview(UUID tenantId, String source, FilterSpec filter, Instant asOf)`
  - `List<Warehouse> warehouses(...)` 는 **삭제** — 호출부를 `valuesOf(..., FieldCatalog.find("warehouse_code"))` 로 바꾼다

- [ ] **Step 1: 실패하는 시험을 쓴다**

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.engine.SnapshotAggregator;
import kr.suhsaechan.palim.reconcile.filter.FieldCatalog;
import kr.suhsaechan.palim.reconcile.filter.FilterNode;
import kr.suhsaechan.palim.reconcile.filter.FilterOperator;
import kr.suhsaechan.palim.reconcile.filter.FilterSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 화면이 <b>담긴 자료에 실제로 있는 값</b>을 보여준다.
 *
 * <p>커넥터 설정이 아니라 자료에서 뽑는 이유가 있다 — 설정에만 있고 자료가 없는 값을 고르면
 * 대조 대상이 통째로 비는데, 화면은 「고르긴 골랐다」 고 보이므로 원인을 찾기 어렵다.
 *
 * <p>수량을 함께 주는 이유도 같다. 어느 창고가 맡긴 분인지는 <b>규모로 판단</b>하게 된다 —
 * 이름만으로는 알 수 없다.
 */
class FilterOptionsIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private SnapshotAggregator aggregator;
    @Autowired private JdbcClient jdbcClient;

    private Instant baseAt;
    private String source;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        baseAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        source = "src-" + UUID.randomUUID().toString().substring(0, 6);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("어느 칸이든 값 후보를 수량과 함께 준다")
    void listsValuesOfAnyField() {
        snapshot("A", "900", "01", "정상", "{}");
        snapshot("B", "100", "02", "정상", "{}");
        snapshot("C", "50", "02", "불량", "{}");

        var warehouses = aggregator.valuesOf(TENANT, source,
                FieldCatalog.find("warehouse_code").orElseThrow());
        var qualities = aggregator.valuesOf(TENANT, source,
                FieldCatalog.find("quality_status").orElseThrow());

        // 규모가 큰 것이 앞에 온다 — 맡긴 창고를 규모로 알아본다.
        assertThat(warehouses).extracting(SnapshotAggregator.FieldValue::value)
                .containsExactly("01", "02");
        assertThat(warehouses.get(0).qty()).isEqualByComparingTo("900");
        assertThat(qualities).extracting(SnapshotAggregator.FieldValue::value)
                .containsExactlyInAnyOrder("정상", "불량");
    }

    @Test
    @DisplayName("원천 고유 칸의 이름을 담긴 자료에서 찾는다 — 계정이 바뀌어도 화면이 돈다")
    void findsAttributeKeys() {
        snapshot("A", "10", "01", "정상", "{\"재고구분\": \"정상\", \"입고차수\": \"3\"}");

        assertThat(aggregator.attributeKeys(TENANT, source))
                .containsExactlyInAnyOrder("재고구분", "입고차수");
    }

    @Test
    @DisplayName("조건을 걸면 몇 줄이 남는지 저장 전에 알려준다")
    void previewsBeforeSaving() {
        snapshot("A", "900", "01", "정상", "{}");
        snapshot("B", "100", "02", "정상", "{}");

        var all = aggregator.preview(TENANT, source, FilterSpec.all(), baseAt);
        var narrowed = aggregator.preview(TENANT, source,
                new FilterSpec(new FilterNode.Compare(
                        FieldCatalog.find("warehouse_code").orElseThrow(),
                        FilterOperator.IN, List.of("01"))),
                baseAt);

        assertThat(all.totalItems()).isEqualTo(2);
        assertThat(all.keptItems()).isEqualTo(2);
        assertThat(narrowed.totalItems()).isEqualTo(2);
        assertThat(narrowed.keptItems()).isEqualTo(1);
        assertThat(narrowed.keptQty()).isEqualByComparingTo("900");
    }

    @Test
    @DisplayName("값이 없는 칸은 빈 목록이다 — 「담긴 자료가 없습니다」 를 화면이 말한다")
    void emptyWhenNothingLoaded() {
        assertThat(aggregator.valuesOf(TENANT, source,
                FieldCatalog.find("lot_code").orElseThrow())).isEmpty();
    }

    private void snapshot(String itemRef, String qty, String warehouse, String quality,
                          String attributesJson) {
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code,
                             warehouse_name, lot_code, quality_status, quantity, base_quantity,
                             base_unit, raw_item_name, attributes, created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, :warehouse, :whName, '',
                                :quality, :qty, :qty, 'EA', :name, cast(:attrs as jsonb),
                                :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", baseAt.atOffset(ZoneOffset.UTC))
                .param("source", source)
                .param("warehouse", warehouse)
                .param("whName", "창고" + warehouse)
                .param("quality", quality)
                .param("qty", new BigDecimal(qty))
                .param("name", "품목 " + itemRef)
                .param("attrs", attributesJson)
                .update();
    }
}
```

- [ ] **Step 2: 시험을 돌려 실패를 확인한다**

Run: `./gradlew :palim-app:test --tests "*FilterOptionsIntegrationTest*"`
Expected: 컴파일 실패 — `valuesOf`·`attributeKeys`·`preview` 없음

- [ ] **Step 3: `valuesOf` 를 만들고 `warehouses` 를 지운다**

```java
    /**
     * 이 원천에 <b>실제로 들어온</b> 값들.
     *
     * <p>커넥터 설정이 아니라 담긴 자료에서 뽑는다 — 설정에만 있고 자료가 없는 값을 고르면
     * 대조 대상이 통째로 비는데, 화면은 「고르긴 골랐다」 고 보이므로 원인을 찾기 어렵다.
     *
     * <p>수량을 함께 준다. 어느 창고가 맡긴 분인지는 <b>규모로 판단</b>하게 되기 때문이다 —
     * 이름만으로는 알 수 없다.
     *
     * <p>가장 최근 기준 시각의 자료만 본다. 옛 회차에만 있던 값이 목록에 남으면 지금은 쓰지
     * 않는 것을 고르게 된다.
     *
     * <p>표현식은 <b>카탈로그를 거친 것만</b> 온다 — 부르는 쪽이 임의 문자열을 넘길 수 없다.
     */
    @Transactional(readOnly = true)
    public List<FieldValue> valuesOf(UUID tenantId, String source, FilterableField field) {
        String column = field.sqlWith("s");
        // 창고는 이름 칸이 따로 있다. 그 칸이 있으면 함께 보여 사람이 알아볼 수 있게 한다.
        String labelColumn = "warehouse_code".equals(field.key())
                ? "max(coalesce(s.warehouse_name, ''))" : "''";

        return jdbcClient.sql("""
                        SELECT coalesce(%s, '')  AS value,
                               %s                AS label,
                               count(*)::int     AS items,
                               sum(s.base_quantity) AS qty
                          FROM std_stock_snapshot s
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = (SELECT max(x.base_at) FROM std_stock_snapshot x
                                               WHERE x.tenant_id = :tenantId
                                                 AND x.source    = :source)
                         GROUP BY coalesce(%s, '')
                         ORDER BY sum(s.base_quantity) DESC
                         LIMIT 200
                        """.formatted(column, labelColumn, column))
                .param("tenantId", tenantId)
                .param("source", source)
                .query((rs, rowNum) -> new FieldValue(
                        rs.getString("value"), rs.getString("label"),
                        rs.getInt("items"), rs.getBigDecimal("qty")))
                .list();
    }

    /**
     * 걸 수 있는 값 하나.
     *
     * @param value 저장될 값. 원천이 그 칸을 안 주면 빈 문자열이다
     * @param label 곁들일 이름. 창고처럼 이름 칸이 따로 있는 경우에만 채워진다
     * @param items 그 값을 가진 품목 줄 수
     * @param qty   그 값의 수량 합계. <b>무엇을 골라야 하는지 규모로 판단하게 된다</b>
     */
    public record FieldValue(String value, String label, int items, BigDecimal qty) {

        /** 화면에 쓸 이름. 이름이 없으면 값으로 대신한다 — 빈 칸은 고를 수 없다. */
        public String display() {
            if (label != null && !label.isBlank()) {
                return value.isBlank() ? label : "%s (%s)".formatted(label, value);
            }
            return value.isBlank() ? "값 없음" : value;
        }
    }
```

`LIMIT 200` 을 두는 이유 — 품목코드처럼 값이 수만 개인 칸을 고르면 화면이 그것을 전부 그린다. 상한이 있으면 화면이 「200개까지만 보입니다. 검색하거나 식을 쓰세요」 를 말할 수 있다. **말없이 자르지 않는다.**

기존 `warehouses()` 와 `Warehouse` record 를 지우고, `ReconcileController` 의 두 호출을 `valuesOf(tenantId, source, FieldCatalog.find("warehouse_code").orElseThrow())` 로 바꾼다.

- [ ] **Step 4: `attributeKeys` 를 만든다**

```java
    /**
     * 이 원천이 주는 <b>표준에 없는 칸</b>의 이름들.
     *
     * <p>매핑되지 않은 원천 컬럼을 {@code attributes} 에 통째로 살려 두므로, 그 키를 뽑으면
     * <b>원천 계정이 바뀌어 칸 구성이 달라져도</b> 화면이 그대로 동작한다.
     */
    @Transactional(readOnly = true)
    public List<String> attributeKeys(UUID tenantId, String source) {
        return jdbcClient.sql("""
                        SELECT DISTINCT k AS key
                          FROM std_stock_snapshot s,
                               LATERAL jsonb_object_keys(s.attributes) AS k
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = (SELECT max(x.base_at) FROM std_stock_snapshot x
                                               WHERE x.tenant_id = :tenantId
                                                 AND x.source    = :source)
                         ORDER BY k
                         LIMIT 200
                        """)
                .param("tenantId", tenantId)
                .param("source", source)
                .query((rs, rowNum) -> rs.getString("key"))
                .list();
    }
```

> `jsonb_object_keys` 와 `LATERAL` 은 PG9.3+ 다 — 운영 14 에서 안전하다.

- [ ] **Step 5: `preview` 를 만든다**

```java
    /**
     * 이 조건이면 몇 줄이 남는가.
     *
     * <p><b>저장 전에 보여준다.</b> 이것이 없으면 저장하고 대조를 돌려 봐야 결과를 안다 —
     * 그리고 그때는 이미 지난 회차의 숫자가 바뀐 뒤다.
     */
    @Transactional(readOnly = true)
    public Preview preview(UUID tenantId, String source, FilterSpec filter, Instant asOf) {
        FilterSpec.Compiled where = filter.compile("s", FilterSpec.PREFIX, asOf);

        return jdbcClient.sql("""
                        SELECT count(*)::int AS total,
                               count(*) FILTER (WHERE true%s)::int AS kept,
                               coalesce(sum(s.base_quantity) FILTER (WHERE true%s), 0) AS kept_qty
                          FROM std_stock_snapshot s
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = (SELECT max(x.base_at) FROM std_stock_snapshot x
                                               WHERE x.tenant_id = :tenantId
                                                 AND x.source    = :source)
                        """.formatted(where.sql(), where.sql()))
                .param("tenantId", tenantId)
                .param("source", source)
                .params(where.params())
                .query((rs, rowNum) -> new Preview(
                        rs.getInt("total"), rs.getInt("kept"), rs.getBigDecimal("kept_qty")))
                .single();
    }

    /**
     * 조건을 걸었을 때 남는 것.
     *
     * @param totalItems 조건 없이 담긴 줄 수
     * @param keptItems  조건을 걸고 남는 줄 수
     * @param keptQty    남는 줄의 수량 합계
     */
    public record Preview(int totalItems, int keptItems, BigDecimal keptQty) {
    }
```

> `FILTER (WHERE ...)` 절은 PG9.4+ 다. `where.sql()` 이 ` AND …` 로 시작하므로 `WHERE true` 뒤에
> 그대로 이어 붙는다. 조건이 없으면 빈 문자열이라 `WHERE true` 만 남아 전부 센다.
>
> **같은 조각을 두 번 쓰지만 바인딩은 한 벌이다.** `where.params()` 를 한 번만 넘기고 이름이
> 같으므로 두 자리가 같은 값을 본다 — 이름을 순번으로 뽑는 덕에 성립한다.

- [ ] **Step 6: 시험을 돌려 통과를 확인한다**

Run: `./gradlew :palim-app:test --tests "*FilterOptionsIntegrationTest*" --tests "*Reconcile*"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "창고 말고 다른 조건으로는 대조를 좁힐 수가 없다 : feat : 값 후보를 담긴 자료에서 뽑고 저장 전에 몇 줄이 남는지 보임 https://github.com/Cassiiopeia/palim/issues/161"
```

---

### Task 9: 조건 편집 화면

`/reconcile/{id}` 의 「견줄 창고」 카드를 「볼 조건」 으로 넓힌다. **이 화면이 이번 작업의 목적이다** — 여기까지 오면 조건을 걸 수 있게 된다.

`docs/11-UI-RULES.md` 를 따른다. 특히:
- **B5** 첫 스크롤에 색 박스는 최대 1개 — 「조건을 안 걸었다」 경고 하나만 색을 쓴다
- **B3** 상시 설명은 색 없는 텍스트. 「고급」 안내에 alert 를 쓰지 않는다
- **N2** 한 화면의 `btn-primary` 는 1개 — 이미 「지금 맞춰 보기」 가 쓰고 있으므로 저장 버튼은 `btn btn-sm`
- **N7** 폼 제출 라벨은 「저장」
- **T7** `<b>` 는 한 문단에 최대 1개

**Files:**
- Create: `palim-web/src/main/java/kr/suhsaechan/palim/web/reconcile/FilterController.java`
- Create: `palim-web/src/main/java/kr/suhsaechan/palim/web/reconcile/FilterEditView.java`
- Modify: `palim-web/src/main/java/kr/suhsaechan/palim/web/reconcile/ReconcileController.java:128-145` — `detail` 이 조건 편집에 필요한 것을 담는다. `changeWarehouses` 핸들러(`:156-179`)는 **삭제**
- Modify: `palim-web/src/main/resources/templates/reconcile/detail.html:47-125` — 「견줄 창고」 카드를 「볼 조건」 으로
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/FilterScreenIntegrationTest.java`

**Interfaces:**
- Consumes: `FieldCatalog`·`FilterableField`·`FieldType` (T1), `FilterOperator` (T2), `FilterService`·`FilterRow`·`FilterSide` (T4·T5), `ExpressionParser` (T7), `SnapshotAggregator.valuesOf`·`attributeKeys`·`preview` (T8)
- Produces:
  - `record FilterEditView(FilterSide side, String source, List<FilterRow> rows, String expression, List<FilterableField> fields, Map<String, List<SnapshotAggregator.FieldValue>> valuesByField, SnapshotAggregator.Preview preview)`
  - `POST /reconcile/{id}/filters` — 한 side 의 줄 전체와 식을 통째로 저장
  - `POST /reconcile/{id}/filters/preview` — 저장하지 않고 몇 줄 남는지만

- [ ] **Step 1: 실패하는 시험을 쓴다**

```java
package kr.suhsaechan.palim.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.filter.FilterRowRepository;
import kr.suhsaechan.palim.reconcile.filter.FilterSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 조건을 거는 화면.
 *
 * <p>「견줄 창고」 하나만 있던 자리를 「볼 조건」 으로 넓힌다. 여기까지 와야 사장님이 실제로
 * 조건을 걸 수 있다 — 그 전까지는 코드에만 있는 기능이다.
 */
@WithMockUser(roles = "ADMIN")
class FilterScreenIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mvc;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private FilterRowRepository filterRows;
    @Autowired private JdbcClient jdbcClient;

    private Instant baseAt;
    private String left;
    private String right;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        baseAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        left = "l-" + UUID.randomUUID().toString().substring(0, 6);
        right = "r-" + UUID.randomUUID().toString().substring(0, 6);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("화면이 담긴 자료의 값 후보를 수량과 함께 그린다")
    void rendersValueOptions() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "9426", "01");
        snapshot(left, "B", "312", "02");

        mvc.perform(get("/reconcile/" + definition.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("볼 조건")))
                .andExpect(content().string(containsString("9,426")))
                .andExpect(content().string(containsString("창고")));
    }

    @Test
    @DisplayName("걸 수 있는 칸에 창고가 아닌 것도 나온다")
    void rendersNonWarehouseFields() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");

        mvc.perform(get("/reconcile/" + definition.getId()))
                .andExpect(content().string(containsString("품질상태")))
                .andExpect(content().string(containsString("유통기한")));
    }

    @Test
    @DisplayName("조건 줄을 저장하면 다시 읽힌다")
    void savesRows() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");

        mvc.perform(post("/reconcile/" + definition.getId() + "/filters")
                        .param("side", "LEFT")
                        .param("fieldKey", "warehouse_code", "quality_status")
                        .param("operator", "IN", "NOT_IN")
                        .param("values", "01|02", "불량")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        var saved = filterRows.findByDefinitionIdOrderBySideAscOrdinalAsc(definition.getId());
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getValues()).containsExactly("01", "02");
        assertThat(saved.get(1).getValues()).containsExactly("불량");
    }

    @Test
    @DisplayName("식도 함께 저장되고 조건 줄과 AND 로 걸린다")
    void savesExpression() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");

        mvc.perform(post("/reconcile/" + definition.getId() + "/filters")
                        .param("side", "LEFT")
                        .param("fieldKey", "warehouse_code")
                        .param("operator", "IN")
                        .param("values", "01")
                        .param("expression", "품질상태 = '정상' 또는 품질상태 비었음")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        var saved = filterRows.findByDefinitionIdOrderBySideAscOrdinalAsc(definition.getId());
        assertThat(saved).hasSize(2);
        assertThat(saved.get(1).isExpression()).isTrue();
    }

    @Test
    @DisplayName("읽을 수 없는 식은 저장이 막히고 어디서 막혔는지 말한다")
    void rejectsBadExpression() throws Exception {
        ReconcileDefinition definition = definition();

        mvc.perform(post("/reconcile/" + definition.getId() + "/filters")
                        .param("side", "LEFT")
                        .param("expression", "창고 = '01'; DROP TABLE std_stock_snapshot")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError",
                        containsString("읽지 못했습니다")));

        assertThat(filterRows.findByDefinitionIdOrderBySideAscOrdinalAsc(definition.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("조건을 안 걸었는데 창고가 여럿이면 경고가 뜬다")
    void warnsWhenNothingChosen() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");
        snapshot(left, "B", "20", "02");

        mvc.perform(get("/reconcile/" + definition.getId()))
                .andExpect(content().string(containsString("전부 더해서")));
    }

    private ReconcileDefinition definition() {
        return definitions.save(ReconcileDefinition.of(TENANT,
                "DEF-" + UUID.randomUUID().toString().substring(0, 8), "시험 대조",
                left, right, "base_quantity", BigDecimal.ZERO, null));
    }

    private void snapshot(String source, String itemRef, String qty, String warehouse) {
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             quantity, base_quantity, base_unit, raw_item_name,
                             created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, :warehouse, '',
                                :qty, :qty, 'EA', :name, :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", baseAt.atOffset(ZoneOffset.UTC))
                .param("source", source)
                .param("warehouse", warehouse)
                .param("qty", new BigDecimal(qty))
                .param("name", "품목 " + itemRef)
                .update();
    }
}
```

> `csrf()`·`flash()`·`assertThat` 의 static import 는 다른 화면 시험(`ReconcileScreenRenderIntegrationTest`)에서 쓰는 것을 그대로 따른다. 구현 시 그 파일을 열어 맞춘다.

- [ ] **Step 2: 시험을 돌려 실패를 확인한다**

Run: `./gradlew :palim-app:test --tests "*FilterScreenIntegrationTest*"`
Expected: 실패 — 화면에 「볼 조건」 이 없다

- [ ] **Step 3: `FilterEditView` 를 만든다**

```java
package kr.suhsaechan.palim.web.reconcile;

import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.reconcile.engine.SnapshotAggregator;
import kr.suhsaechan.palim.reconcile.filter.FilterRow;
import kr.suhsaechan.palim.reconcile.filter.FilterSide;
import kr.suhsaechan.palim.reconcile.filter.FilterableField;

/**
 * 한쪽 원천의 조건 편집기에 필요한 것 전부.
 *
 * <p>화면이 «네 가지를 따로 모델에 담는» 대신 한 묶음으로 받는다. 좌·우 두 벌이라 따로 담으면
 * 이름이 여덟 개가 되고, 그중 하나를 빠뜨려도 화면은 조용히 빈 칸을 그린다.
 *
 * @param side          어느 쪽인가
 * @param source        그 원천 이름. 화면 제목에 쓴다
 * @param rows          지금 걸린 조건 줄
 * @param expression    지금 걸린 식. 없으면 빈 문자열
 * @param fields        걸 수 있는 칸. 표준 칸 + 이 원천이 실제로 주는 고유 칸
 * @param valuesByField 칸별 값 후보. 글 칸만 담긴다 — 숫자·날짜는 목록이 뜻이 없다
 * @param preview       지금 조건이면 몇 줄이 남는지
 */
public record FilterEditView(FilterSide side, String source,
                             List<FilterRow> rows, String expression,
                             List<FilterableField> fields,
                             Map<String, List<SnapshotAggregator.FieldValue>> valuesByField,
                             SnapshotAggregator.Preview preview) {

    /** 값 후보가 여럿인데 아무 조건도 안 걸린 상태인가. 화면이 경고를 띄울 자리다. */
    public boolean needsChoice() {
        return rows.isEmpty() && expression.isBlank()
                && valuesByField.getOrDefault("warehouse_code", List.of()).size() > 1;
    }
}
```

- [ ] **Step 4: `FilterController` 를 만든다**

```java
package kr.suhsaechan.palim.web.reconcile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.filter.ExpressionParser;
import kr.suhsaechan.palim.reconcile.filter.FilterOperator;
import kr.suhsaechan.palim.reconcile.filter.FilterRow;
import kr.suhsaechan.palim.reconcile.filter.FilterRowRepository;
import kr.suhsaechan.palim.reconcile.filter.FilterSide;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * <b>무엇을 볼지</b> 정한다.
 *
 * <p>재고를 맡긴 곳은 자기가 보관 중인 것만 안다. 전산 쪽 창고를 전부 더해 견주면 맡기지 않은
 * 물량만큼 무조건 어긋나고, 그 어긋남은 맞던 품목까지 틀린 것으로 보이게 만든다. 그런데 걸러야
 * 하는 것은 창고만이 아니다 — 불량 재고, 유통기한이 지난 것, 원천이 주는 고유 구분값.
 *
 * <p>한 side 의 줄을 <b>통째로 갈아 끼운다.</b> 줄마다 id 를 주고받으면 화면을 띄운 뒤 다른
 * 사람이 줄을 지웠을 때 「없는 줄을 고치려 했습니다」 가 된다. 통째로 보내면 마지막에 저장한
 * 사람의 뜻이 그대로 남는다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class FilterController {

    /** 한 줄 안의 여러 값을 잇는 글자. 값 자체에 들어갈 일이 없는 것으로 고른다. */
    private static final String VALUE_DELIMITER = "|";

    private final FilterRowRepository rows;
    private final ErrorMessageResolver errorMessages;

    @PostMapping("/reconcile/{id}/filters")
    @Transactional
    public String save(@PathVariable UUID id,
                       @RequestParam FilterSide side,
                       @RequestParam(name = "fieldKey", required = false) List<String> fieldKeys,
                       @RequestParam(name = "operator", required = false)
                       List<FilterOperator> operators,
                       @RequestParam(name = "values", required = false) List<String> values,
                       @RequestParam(required = false, defaultValue = "") String expression,
                       RedirectAttributes redirect) {

        List<FilterRow> next = new ArrayList<>();
        try {
            next.addAll(buildRows(id, side, fieldKeys, operators, values));
            if (!expression.isBlank()) {
                // 읽을 수 없으면 여기서 터진다. 저장하고 도는 순간까지 미루지 않는다.
                ExpressionParser.parse(expression);
                next.add(FilterRow.expression(TenantContext.current(), id, side,
                        next.size(), expression.trim()));
            }
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError", errorMessages.resolve(e));
            return "redirect:/reconcile/" + id + "#filters";
        }

        rows.deleteByDefinitionIdAndSide(id, side);
        rows.saveAll(next);

        log.info("대조 조건 변경 — 정의={} 쪽={} 줄={}개 식={}",
                id, side, next.size(), expression.isBlank() ? "없음" : "있음");
        redirect.addFlashAttribute("flashSuccess",
                next.isEmpty()
                        ? "조건을 지웠습니다. 이제 전부 더해서 견줍니다."
                        : "볼 조건을 정했습니다. 다음 대조부터 적용됩니다.");
        return "redirect:/reconcile/" + id + "#filters";
    }

    /**
     * 화면이 보낸 세 목록을 줄로 맞춘다.
     *
     * <p>길이가 어긋나면 <b>저장하지 않는다.</b> 짧은 쪽에 맞춰 자르면 사람이 적은 조건 일부가
     * 조용히 사라지고, 화면은 「저장했습니다」 라고 말한다.
     */
    private List<FilterRow> buildRows(UUID definitionId, FilterSide side,
                                      List<String> fieldKeys, List<FilterOperator> operators,
                                      List<String> values) {
        if (fieldKeys == null || fieldKeys.isEmpty()) {
            return List.of();
        }
        if (operators == null || values == null
                || fieldKeys.size() != operators.size() || fieldKeys.size() != values.size()) {
            throw new BusinessException(
                    kr.suhsaechan.palim.common.error.ErrorCode.FILTER_VALUE_COUNT,
                    "조건 줄", fieldKeys.size());
        }

        UUID tenantId = TenantContext.current();
        List<FilterRow> built = new ArrayList<>();
        for (int i = 0; i < fieldKeys.size(); i++) {
            String raw = values.get(i);
            List<String> parsed = raw == null || raw.isBlank()
                    ? List.of()
                    : List.of(raw.split("\\" + VALUE_DELIMITER, -1)).stream()
                            .map(String::trim).filter(v -> !v.isEmpty()).toList();
            built.add(FilterRow.field(tenantId, definitionId, side, i,
                    fieldKeys.get(i), operators.get(i), parsed));
        }
        return built;
    }
}
```

> 저장된 줄이 실제로 SQL 이 되는지는 `FilterCompiler` 가 판정한다. 여기서는 모양만 맞추고,
> 다음 화면 그리기에서 `FilterService.specOf` 가 불리며 잘못된 줄이 드러난다. **잘못된 줄을
> 저장하고 나서 드러나는 것을 막으려면**, `rows.saveAll` 앞에 `compiler.compile(next)` 를 한 번
> 부른다 — 구현 시 `FilterCompiler` 를 주입해 그 호출을 넣는다.

- [ ] **Step 5: `ReconcileController.detail` 을 넓히고 `changeWarehouses` 를 지운다**

`detail` 이 좌·우 `FilterEditView` 를 담는다.

```java
    @GetMapping("/reconcile/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        ReconcileDefinition definition = definitions.findById(id).orElseThrow();
        UUID tenantId = TenantContext.current();

        model.addAttribute("title", definition.getName() + " · 대조");
        model.addAttribute("definition", definition);
        model.addAttribute("runs", runs.findByDefinitionIdOrderByStartedAtDesc(id));
        model.addAttribute("granularities", BaseAtGranularity.values());
        model.addAttribute("leftEdit",
                editView(tenantId, id, FilterSide.LEFT, definition.getLeftSource()));
        model.addAttribute("rightEdit",
                editView(tenantId, id, FilterSide.RIGHT, definition.getRightSource()));
        model.addAttribute("operators", FilterOperator.values());
        return "reconcile/detail";
    }

    /**
     * 한쪽 조건 편집기에 필요한 것.
     *
     * <p>값 후보는 <b>글 칸만</b> 뽑는다. 숫자·날짜는 있을 수 있는 값이 사실상 무한이라 목록이
     * 뜻이 없고, 뽑는 비용만 든다.
     */
    private FilterEditView editView(UUID tenantId, UUID definitionId,
                                    FilterSide side, String source) {
        List<FilterableField> fields = new ArrayList<>(FieldCatalog.standard());
        fields.addAll(FieldCatalog.attributeFields(
                aggregator.attributeKeys(tenantId, source)));

        Map<String, List<SnapshotAggregator.FieldValue>> values = new LinkedHashMap<>();
        for (FilterableField field : fields) {
            if (field.type() == FieldType.TEXT) {
                values.put(field.key(), aggregator.valuesOf(tenantId, source, field));
            }
        }

        List<FilterRow> saved = filters.rowsOf(definitionId, side);
        String expression = saved.stream().filter(FilterRow::isExpression)
                .map(FilterRow::getExpression).findFirst().orElse("");

        return new FilterEditView(side, source,
                saved.stream().filter(row -> !row.isExpression()).toList(),
                expression, fields, values,
                aggregator.preview(tenantId, source,
                        filters.specOf(definitionId, side), Instant.now()));
    }
```

`changeWarehouses` 핸들러와 `WarehouseScope` import 를 지운다.

- [ ] **Step 6: `detail.html` 의 「견줄 창고」 카드를 바꾼다**

```html
<!--/* 재고를 맡긴 곳은 «자기가 보관 중인 것만» 안다. 그런데 걸러야 하는 것은 창고만이 아니다 —
       불량 재고, 유통기한이 지난 것, 원천이 주는 고유 구분값. 칸마다 화면을 새로 만들면 다음
       요구에서 또 막히므로, 거르는 방법을 하나 두고 창고를 그중 한 줄로 둔다. */-->
<div class="card bg-base-100 shadow-sm mb-6" id="filters">
    <div class="card-body p-4">
        <h2 class="card-title text-base">볼 조건</h2>

        <!--/* 조건을 안 걸었는데 값이 여럿이면 «조용히 틀린 답» 이 나온다. 지금 이 화면에서
               확인이 필요한 상태이므로 경고색이 맞다(11-UI-RULES C7·B5 — 이 화면의 색 박스는
               이것 하나뿐이다). */-->
        <div th:if="${leftEdit.needsChoice() or rightEdit.needsChoice()}"
             class="alert alert-warning" role="alert">
            <div>
                <p class="font-medium">조건을 걸지 않고 전부 더해서 견주고 있습니다.</p>
                <p class="mt-1">
                    한쪽에 창고가 여럿이면 맡기지 않은 물량까지 합산됩니다. 그러면 맞는 품목도
                    틀린 것으로 나옵니다 — 볼 조건을 정해 주세요.
                </p>
            </div>
        </div>

        <div class="flex flex-wrap gap-x-10 gap-y-6">
            <div th:each="edit : ${ {leftEdit, rightEdit} }" class="grow min-w-80">
                <form method="post"
                      th:action="@{/reconcile/{id}/filters(id=${definition.id})}">
                    <input type="hidden" name="side" th:value="${edit.side}">

                    <div class="text-xs text-base-content/60 mb-2"
                         th:text="${edit.source}">원천</div>

                    <p th:if="${#lists.isEmpty(edit.fields)}" class="text-base-content/40">
                        담긴 자료가 없습니다
                    </p>

                    <!--/* 조건 줄. 값 위젯이 타입을 따른다 — 글 칸은 담긴 값 체크박스,
                           날짜는 날짜칸, 숫자는 숫자칸. 무엇을 골라야 하는지 규모로 알아본다. */-->
                    <div th:each="row, s : ${edit.rows}" class="flex flex-wrap gap-2 mb-2">
                        <select name="fieldKey" class="select select-bordered select-sm w-40">
                            <option th:each="f : ${edit.fields}"
                                    th:value="${f.key}" th:text="${f.label}"
                                    th:selected="${f.key == row.fieldKey}">칸</option>
                        </select>
                        <select name="operator" class="select select-bordered select-sm w-32">
                            <option th:each="op : ${operators}"
                                    th:value="${op}" th:text="${op.label}"
                                    th:selected="${op == row.operator}">연산자</option>
                        </select>
                        <input type="text" name="values"
                               class="input input-bordered input-sm grow"
                               th:value="${#strings.listJoin(row.values, '|')}">
                    </div>

                    <!--/* 값 후보. 어느 것이 맡긴 분인지 «규모로» 판단하게 되므로 수량을 함께
                           보인다 — 이름만으로는 알 수 없다. */-->
                    <details class="mb-2">
                        <summary class="text-sm cursor-pointer">담긴 값 보기</summary>
                        <div class="text-sm mt-2 space-y-1">
                            <div th:each="entry : ${edit.valuesByField}"
                                 th:if="${!#lists.isEmpty(entry.value)}">
                                <span class="text-base-content/60"
                                      th:text="${entry.key}">칸</span>
                                <span th:each="v : ${entry.value}" class="ml-2">
                                    <span th:text="${v.display()}">값</span>
                                    <span class="text-base-content/60 tabular-nums"
                                          th:text="${#numbers.formatInteger(v.qty, 1, 'COMMA')}">0</span>
                                </span>
                            </div>
                        </div>
                    </details>

                    <!--/* 줄로 안 되는 조건 — 칸을 넘는 OR, 괄호. 대부분은 줄로 끝나므로
                           접어 두되, 내용이 있으면 접힌 채로도 보이게 한다. */-->
                    <details class="mb-2" th:open="${!#strings.isEmpty(edit.expression)}">
                        <summary class="text-sm cursor-pointer">고급 — 줄로 안 되는 조건</summary>
                        <textarea name="expression" rows="2"
                                  class="textarea textarea-bordered textarea-sm w-full mt-2"
                                  placeholder="(창고 = '01' 또는 창고 = '02') 그리고 품질상태 ≠ '불량'"
                                  th:text="${edit.expression}"></textarea>
                        <p class="text-base-content/60 mt-1 text-sm">
                            칸 이름은 위 목록의 이름을 그대로 씁니다. 글은 작은따옴표로 감쌉니다.
                            날짜는 <span class="font-mono">오늘</span> ·
                            <span class="font-mono">오늘+30</span> 처럼 적으면 매일 맞습니다.
                        </p>
                    </details>

                    <p class="text-base-content/60 text-sm">
                        지금 조건이면
                        <span class="tabular-nums"
                              th:text="${edit.preview.totalItems()}">0</span>줄 중
                        <span class="tabular-nums font-medium"
                              th:text="${edit.preview.keptItems()}">0</span>줄 ·
                        합계 <span class="tabular-nums"
                              th:text="${#numbers.formatInteger(edit.preview.keptQty(), 1, 'COMMA')}">0</span>
                    </p>

                    <button type="submit" class="btn btn-sm mt-3">저장</button>
                </form>
            </div>
        </div>

        <p class="text-base-content/60 mt-2 text-sm">
            아무 조건도 걸지 않으면 <b>전부 더해서</b> 견줍니다.
        </p>
    </div>
</div>
```

> 값 칸을 `input[type=text]` 로 두는 것은 **1차 구현**이다. 여러 값을 `|` 로 잇는다. 체크박스
> 위젯은 「담긴 값 보기」 를 눌러 값을 확인하고 손으로 적는 흐름으로 먼저 낸다 — 타입별 위젯을
> 자바스크립트 없이 서버 렌더로 만들면 줄을 더할 때마다 폼을 다시 그려야 하고, 그 왕복이
> 이 화면의 쓸모를 떨어뜨린다. **줄을 더하고 지우는 UI 는 Task 10 에서 최소한의 스크립트로
> 붙인다.**

- [ ] **Step 7: 시험을 돌려 통과를 확인한다**

Run: `./gradlew :palim-app:test --tests "*FilterScreenIntegrationTest*" --tests "*ReconcileScreenRenderIntegrationTest*"`
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "창고 말고 다른 조건으로는 대조를 좁힐 수가 없다 : feat : 견줄 창고 자리를 볼 조건으로 넓히고 저장 전에 남는 줄 수를 보임 https://github.com/Cassiiopeia/palim/issues/161"
```

---

### Task 10: 조건을 보여주고 갈 길을 낸다 · 줄 더하기 · 문서

**이 과제가 원래 문제를 푼다.** 품목 묶기 화면에 창고가 한 글자도 없어서, 보고 있는 수치가 전 창고 합인지 한 창고인지 알 수 없었다. 그리고 거기서 조건을 고치러 갈 길이 없었다.

**Files:**
- Modify: `palim-web/src/main/resources/templates/reconcile/units.html:73-87`
- Modify: `palim-web/src/main/java/kr/suhsaechan/palim/web/reconcile/UnitController.java:63-110`
- Modify: `palim-web/src/main/resources/templates/reconcile/run-detail.html`
- Modify: `palim-web/src/main/resources/templates/reconcile/detail.html` — 줄 더하기·지우기
- Modify: `docs/07-DECISIONS.md`
- Modify: `CLAUDE.md` (표에 조건 문서 한 줄이 필요하면)
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/FilterVisibilityIntegrationTest.java`

**Interfaces:**
- Consumes: `FilterService`·`Pairing` (T5), `FilterSnapshot` (T6), `FilterSpec.describe()` (T7)
- Produces: 새 공개 타입 없음 — 화면만 바뀐다

- [ ] **Step 1: 실패하는 시험을 쓴다**

```java
package kr.suhsaechan.palim.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.filter.FilterOperator;
import kr.suhsaechan.palim.reconcile.filter.FilterRow;
import kr.suhsaechan.palim.reconcile.filter.FilterRowRepository;
import kr.suhsaechan.palim.reconcile.filter.FilterSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 지금 걸린 조건이 <b>일하는 화면에</b> 보인다.
 *
 * <p>이것이 없어서 실제로 막혔다 — 품목 묶기 화면에 창고가 한 글자도 없어, 보고 있는 수치가
 * 전 창고 합인지 한 창고인지 알 수 없었다. 그리고 거기서 조건을 고치러 갈 링크도 없었다.
 * 사이드바의 「재고 맞추기」 는 클릭되지 않는 제목이라 길을 더 못 찾는다.
 */
@WithMockUser(roles = "ADMIN")
class FilterVisibilityIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mvc;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private FilterRowRepository filterRows;
    @Autowired private JdbcClient jdbcClient;

    private Instant baseAt;
    private String left;
    private String right;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        baseAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        left = "l-" + UUID.randomUUID().toString().substring(0, 6);
        right = "r-" + UUID.randomUUID().toString().substring(0, 6);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("품목 묶기 화면이 지금 걸린 조건을 말한다")
    void unitsScreenShowsFilters() throws Exception {
        ReconcileDefinition definition = definition();
        filterRows.save(FilterRow.field(TENANT, definition.getId(), FilterSide.LEFT, 0,
                "warehouse_code", FilterOperator.IN, List.of("01")));
        snapshot(left, "A", "10", "01");
        snapshot(right, "B", "10", "");

        mvc.perform(get("/reconcile/units").param("definitionId",
                        definition.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("볼 조건")))
                .andExpect(content().string(containsString("창고")))
                .andExpect(content().string(containsString("01")));
    }

    @Test
    @DisplayName("품목 묶기 화면에서 조건을 고치러 갈 수 있다 — 이 길이 없어서 못 찾았다")
    void unitsScreenLinksToFilters() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");
        snapshot(right, "B", "10", "");

        mvc.perform(get("/reconcile/units").param("definitionId",
                        definition.getId().toString()))
                .andExpect(content().string(containsString(
                        "/reconcile/" + definition.getId() + "#filters")));
    }

    @Test
    @DisplayName("조건이 없으면 「전부 더해서 봅니다」 라고 말한다 — 조용히 두지 않는다")
    void saysWhenNoFilter() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");
        snapshot(left, "B", "20", "02");
        snapshot(right, "C", "10", "");

        mvc.perform(get("/reconcile/units").param("definitionId",
                        definition.getId().toString()))
                .andExpect(content().string(containsString("전부 더해서")));
    }

    @Test
    @DisplayName("사이드바에 조건 얘기를 새로 넣지 않는다 — 메뉴는 늘리지 않는다")
    void doesNotAddMenuItem() throws Exception {
        mvc.perform(get("/reconcile"))
                .andExpect(content().string(not(containsString(">볼 조건<"))));
    }

    private ReconcileDefinition definition() {
        return definitions.save(ReconcileDefinition.of(TENANT,
                "DEF-" + UUID.randomUUID().toString().substring(0, 8), "시험 대조",
                left, right, "base_quantity", BigDecimal.ZERO, null));
    }

    private void snapshot(String source, String itemRef, String qty, String warehouse) {
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             quantity, base_quantity, base_unit, raw_item_name,
                             created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, :warehouse, '',
                                :qty, :qty, 'EA', :name, :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", baseAt.atOffset(ZoneOffset.UTC))
                .param("source", source)
                .param("warehouse", warehouse)
                .param("qty", new BigDecimal(qty))
                .param("name", "품목 " + itemRef)
                .update();
    }
}
```

- [ ] **Step 2: 시험을 돌려 실패를 확인한다**

Run: `./gradlew :palim-app:test --tests "*FilterVisibilityIntegrationTest*"`
Expected: 실패 — 품목 묶기 화면에 「볼 조건」 이 없다

- [ ] **Step 3: `UnitController` 가 조건을 모델에 담는다**

`Pairing.of(definition)` 호출을 `filters.pairingOf(definition)` 으로 바꾸고(Task 5 에서 이미 했다면 그대로), 화면에 쓸 두 값을 더한다.

```java
        Pairing pairing = filters.pairingOf(definition);
        MatchBoard.Board loaded = board.load(tenantId, pairing, current, q, page);
        ...
        // 지금 보고 있는 수치가 «전부 더한 것인지 좁힌 것인지» 를 화면이 말해야 한다.
        // 이것이 없어 사장님이 무엇을 보고 있는지 알 수 없었다.
        model.addAttribute("leftFilterText", pairing.leftFilter().describe());
        model.addAttribute("rightFilterText", pairing.rightFilter().describe());
        model.addAttribute("filtersAreAll",
                pairing.leftFilter().isAll() && pairing.rightFilter().isAll());
```

- [ ] **Step 4: `units.html` 의 머리말을 바꾼다**

```html
<!--/* 지금 어느 두 곳을 «어떤 조건으로» 보고 있는가.
       조건은 뒤에서 제대로 걸리는데 화면이 말하지 않아, 보고 있는 수치가 전 창고 합인지 한
       창고인지 알 수 없었다. 그리고 여기서 조건을 고치러 갈 길이 없었다 — 사이드바의
       「재고 맞추기」 는 클릭되지 않는 제목이라 길을 더 못 찾는다. */-->
<p class="text-sm text-base-content/70 mb-1">
    대조
    <b th:text="${definition.name}">대조</b>
    에 적힌 두 곳을 봅니다 —
    <span class="font-mono text-xs" th:text="${definition.leftSource}">좌</span>
    <span class="mx-1 text-base-content/40">↔</span>
    <span class="font-mono text-xs" th:text="${definition.rightSource}">우</span>
    <a th:if="${definitions != null and #lists.size(definitions) > 1}"
       th:href="@{/reconcile/units}" class="link ml-2">다른 대조 보기</a>
</p>

<p class="text-sm mb-4"
   th:classappend="${filtersAreAll} ? 'text-warning' : 'text-base-content/70'">
    <span th:if="${filtersAreAll}">
        볼 조건 없음 — <b>전부 더해서</b> 봅니다.
    </span>
    <span th:unless="${filtersAreAll}">
        볼 조건
        <span class="badge badge-ghost" th:text="${leftFilterText}">좌 조건</span>
        <span class="mx-1 text-base-content/40">↔</span>
        <span class="badge badge-ghost" th:text="${rightFilterText}">우 조건</span>
    </span>
    <a th:href="@{/reconcile/{id}(id=${definition.id})} + '#filters'"
       class="link ml-2">조건 고치기 →</a>
</p>
```

> `badge-ghost` 를 쓰는 이유 — 조건이 걸린 것은 **정상 상태**다. 상태 어휘 넷(성공·경고·오류·
> 정보)에 해당하지 않으므로 색을 켜지 않는다(11-UI-RULES C7). 반대로 조건이 없는 것은 지금
> 확인이 필요한 상태라 `text-warning` 이 맞다.

- [ ] **Step 5: `run-detail.html` 에 그 회차의 조건을 보인다**

`ReconcileController.runDetail` 이 `run.getFilters()` 를 모델에 담고, 화면이 같은 칩으로 그린다.

```html
<p class="text-sm text-base-content/70 mb-4">
    이 회차가 본 조건
    <span class="badge badge-ghost" th:text="${filters.leftExpression()}">좌</span>
    <span class="mx-1 text-base-content/40">↔</span>
    <span class="badge badge-ghost" th:text="${filters.rightExpression()}">우</span>
    <span th:if="${!#lists.isEmpty(filters.resolvedDates())}" class="ml-2">
        <span th:each="d : ${filters.resolvedDates()}" class="text-xs">
            <span th:text="${d.raw()}">오늘+30</span> =
            <span th:text="${d.value()}">2026-09-21</span>
        </span>
    </span>
</p>
```

「어제와 오늘 숫자가 왜 다른지」 가 화면에서 끝난다.

- [ ] **Step 6: 조건 줄을 더하고 지우는 UI 를 붙인다**

`detail.html` 의 조건 줄 묶음에 최소한의 스크립트를 둔다. 서버 왕복 없이 줄을 늘린다.

```html
<div th:id="'rows-' + ${edit.side}">
    <!--/* 위에서 그린 조건 줄들 */-->
</div>
<template th:id="'row-template-' + ${edit.side}">
    <div class="flex flex-wrap gap-2 mb-2">
        <select name="fieldKey" class="select select-bordered select-sm w-40">
            <option th:each="f : ${edit.fields}"
                    th:value="${f.key}" th:text="${f.label}">칸</option>
        </select>
        <select name="operator" class="select select-bordered select-sm w-32">
            <option th:each="op : ${operators}"
                    th:value="${op}" th:text="${op.label}">연산자</option>
        </select>
        <input type="text" name="values" class="input input-bordered input-sm grow">
        <button type="button" class="btn btn-ghost btn-sm" data-remove>지우기</button>
    </div>
</template>
<button type="button" class="btn btn-sm" data-add th:data-side="${edit.side}">
    + 조건 추가
</button>
```

```javascript
// 줄을 더하고 지운다. 서버 왕복을 두면 줄 하나 더할 때마다 화면이 깜빡이고, 적던 값이 날아간다.
document.querySelectorAll('[data-add]').forEach(function (button) {
    button.addEventListener('click', function () {
        var side = button.dataset.side;
        var template = document.getElementById('row-template-' + side);
        document.getElementById('rows-' + side)
                .appendChild(template.content.cloneNode(true));
    });
});
document.addEventListener('click', function (event) {
    if (event.target.matches('[data-remove]')) {
        event.target.closest('div').remove();
    }
});
```

> 스크립트를 두는 이유를 적어 둔다 — 이 저장소는 서버 렌더가 기본이다. 서버 왕복으로 줄을
> 더하면 적던 값이 날아가고, 그 왕복이 이 화면의 쓸모를 떨어뜨린다. **줄이 하나도 없을 때도
> 「+ 조건 추가」 가 보여야 한다** — 그러지 않으면 첫 조건을 걸 방법이 없다.

- [ ] **Step 7: `docs/07-DECISIONS.md` 에 항목을 더한다**

```markdown
### NNN. 대조 조건은 담긴 뒤에 걸고, SQL 은 나무에서만 만든다

**결정** — 대조가 무엇을 볼지 거르는 조건을 «수집 단계» 가 아니라 «대조 정의» 에 둔다. 조건은
드롭다운 줄과 자유 입력 식 두 입구로 받되, 둘 다 하나의 AST 로 모아 SQL 은 그 AST 에서만
만든다. 칸 이름은 카탈로그를 거친 것만 쓴다.

**왜 담긴 뒤인가** — 안 받은 자료는 영영 없다. 재고는 시점 자료라 지난 시점을 다시 받을 수
없고, 조건을 잘못 걸었다는 것을 한 달 뒤에 알면 그 한 달이 통째로 빈다. 담아 두고 거르면
조건을 바꿔도 재수집이 필요 없고, 회차에 조건을 남겨 과거를 재현할 수 있다.

**왜 나무로 모으는가** — 두 입구가 각자 SQL 을 만들면 「줄로 건 것과 식으로 건 것이 다르게
돈다」 가 언젠가 생기고, 그 차이는 숫자로만 드러나 원인을 찾기 어렵다(030·032 에서 같은 성격의
어긋남을 두 번 겪었다). 경로가 하나면 「지금 조건을 식으로 보기」 도 공짜로 나온다.

**왜 금지어 목록이 아닌가** — 자유 입력을 «검사» 로 막는 방식은 막을 것을 전부 알고 있어야
성립해 언젠가 뚫린다. 대신 파싱해서 **다시 만든다** — 사용자 문자열이 SQL 에 이어붙는 자리가
없고, 함수 호출·서브쿼리·세미콜론·주석은 문법에 아예 없다.

**버린 안** — ① 수집 단계 필터: 화면은 깨끗해지지만 안 받은 것은 복구 불가.
② 자유 SQL: 인젝션과 「오타가 도는 순간까지 안 잡힘」. 이 저장소는 PUBLIC 이라 우리 쪽에서
안 터지는 것으로는 부족하다. ③ 칸마다 전용 화면: 다섯 번째 요구에서 무너진다.

**남긴 것** — `reconcile_definition.left_warehouses`·`right_warehouses` 컬럼. V34 에서
`reconcile_filter` 로 옮겼지만 지우지 않는다. 이관이 잘못됐을 때 원본을 볼 곳이 있어야 하고,
컬럼을 지우는 것은 되돌릴 수 없다.
```

번호는 파일의 마지막 항목 다음 번호를 쓴다.

- [ ] **Step 8: 시험 전체를 돌린다**

Run: `./gradlew build`
Expected: 전부 PASS. 로컬에서 Gradle 배포판 내려받기가 막히면 push 후 GitHub Actions 결과로 확인한다.

- [ ] **Step 9: 커밋하고 PR 을 연다**

```bash
git add -A
git commit -m "창고 말고 다른 조건으로는 대조를 좁힐 수가 없다 : feat : 품목 묶기와 대조 결과에 지금 걸린 조건을 보이고 고치러 갈 길을 냄 https://github.com/Cassiiopeia/palim/issues/161"
git push -u origin 20260822_#161_창고_말고_다른_조건으로는_대조를_좁힐_수가_없다
```

CI 통과를 확인한 뒤 `develop` 으로 PR 을 연다.

---

## 마지막 확인

계획을 끝낸 뒤 스펙의 검증 항목이 전부 시험으로 있는지 대조한다.

| 스펙의 검증 항목 | 어느 시험 |
|---|---|
| ① 조건 없으면 지금과 같은 답 | `FilterQueryIntegrationTest.noFilterMeansEverything` |
| ② 창고 CSV 이관 후 결과가 같음 | `FilterStoreIntegrationTest.migratesWarehouseCsv` |
| ③ 좌·우 조건이 안 섞임 | `FilterSpecTest.prefixKeepsNamesApart` · `FilterQueryIntegrationTest.sidesStayApart` |
| ④ 카탈로그에 없는 칸은 실행 거부 | `FieldCatalogTest.rejectsUnknownField` · `FilterStoreIntegrationTest.unknownFieldIsReported` |
| ⑤ 회차 조건이 남아 지난 회차가 안 변함 | `FilterRunSnapshotIntegrationTest.pastRunKeepsItsFilters` |
| ⑥ 네 쿼리가 같은 숫자 | `ReconcileEngineIntegrationTest` · `MatchBoardIntegrationTest` · `UnitBreakdownIntegrationTest` · `SnapshotAggregatorIntegrationTest` (Task 5 Step 10) |
| ⑦ 값 0개인 `IN` 이 `IN ()` 을 안 만듦 | `FilterOperatorTest.valueCounts` · `ExpressionParserTest.rejectsWrongValueCount` |
| ⑧ 상대 날짜가 회차 기준 시각으로 풀림 | `DateTokenTest` · `FilterSpecTest.relativeDateFollowsRunTime` |
| ⑨ 값 개수 위반 시 저장 거부 | `FilterStoreIntegrationTest` · `ExpressionParserTest.rejectsWrongValueCount` |
| ⑩ 인젝션 시도가 전부 읽기 실패 | `ExpressionInjectionTest` |
| ⑪ 길이·깊이·노드 상한 | `ExpressionInjectionTest.refusesOversized` |
| ⑫ 조건 줄과 식이 같은 SQL | `ExpressionWriterTest.roundTrips` |
