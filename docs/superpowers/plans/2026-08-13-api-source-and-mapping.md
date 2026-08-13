# API 원천 수집과 칸 연결 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** API 로 연결한 원천에서 매일 자동으로 자료를 받아 표준 모델에 적재하고, 그 연결을 개발자가 아닌 사람이 화면에서 끝낼 수 있게 한다.

**Architecture:** `SourceReader` 어댑터 뼈대는 이미 있고 `HTTP_API` 구현체만 없다. 그 하나를 채우면 매핑 화면·시험 실행·자동 수집이 전부 API 로 흐른다. 인증 로직은 검증용(`ZoneSessionProbe`)에서 떼어내 실행 경로와 공유한다. 화면은 원천 값을 보여주고 고르게 하며, 추천 엔진이 미리 골라 둔다.

**Tech Stack:** Java 25 · Spring Boot 4.1 · PostgreSQL 14 · Flyway · Thymeleaf + daisyUI · Testcontainers

## Global Constraints

- PostgreSQL **14 문법만** — `NULLS NOT DISTINCT`(15+) · `MERGE`(15+) · `ANY_VALUE`(16+) 금지. 자연키는 `NOT NULL DEFAULT ''`
- Testcontainers 는 `postgres:14-alpine` 고정. 올리지 않는다
- Jackson 은 `tools.jackson.databind` / `tools.jackson.core`. 애노테이션만 `com.fasterxml.jackson.annotation`
- `JdbcClient` 에 `Instant` 바인딩 불가 — `timestamptz` 에는 `OffsetDateTime`. `count(*)::int` 로 캐스팅
- 전 계층 `Instant`, DB `timestamptz`. `LocalDateTime` 금지
- 예외는 `BusinessException` + `ErrorCode` 만. 새 실패 유형은 `ErrorCode` 한 줄 + `errors.properties`·`errors_en.properties` 각 한 줄
- 동결 도메인 수정 금지: `palim-sku` · `palim-order` · `palim-collector` · `palim-channel` · `palim-mapping` · `palim-incident`
- 확장 대상은 `palim-connector` · `palim-web` 뿐
- 공개 저장소 — 발주사 상호·품목명·회사코드, 서버 호스트·공인 IP·포트를 코드·주석·테스트·커밋 메시지 어디에도 쓰지 않는다. 테스트는 합성 값(`123456`, RFC 5737 대역 `203.0.113.x`)
- 커밋 메시지에 AI 흔적 금지 (`Co-Authored-By` · `Generated with` · 🤖). CI `guard` 잡이 검사한다
- 커밋 형식: `{이슈제목} : {타입} : {설명} {이슈URL}` — `/pro-commit` 사용
- 빌드: `JAVA_HOME=<JDK21경로> ./gradlew build -Porg.gradle.java.installations.paths=<JDK25경로>`
- 기존 테스트 391건 통과 유지

---

### Task 1: 고정값 매핑 (`TransformType.CONSTANT`)

원천에 없는 항목(이카운트는 「단위」 칸을 주지 않는다)을 사람이 직접 적어 넣게 하려면, 원천 칸 없이 값만 갖는 매핑을 표현할 수 있어야 한다.

**Files:**
- Modify: `palim-connector/src/main/java/kr/suhsaechan/palim/connector/transform/TransformType.java`
- Modify: `palim-connector/src/main/java/kr/suhsaechan/palim/connector/transform/TransformEngine.java`
- Test: `palim-connector/src/test/java/kr/suhsaechan/palim/connector/transform/TransformEngineConstantTest.java`

**Interfaces:**
- Produces: `TransformType.CONSTANT` — `rule.param("value", "")` 를 모든 행에 넣는다. `FieldMapping.sourceField()` 는 비어 있어도 된다

- [ ] **Step 1: 실패하는 테스트 작성**

`palim-connector/src/test/java/kr/suhsaechan/palim/connector/transform/TransformEngineConstantTest.java`:

```java
package kr.suhsaechan.palim.connector.transform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.connector.model.FieldDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 원천에 없는 항목을 사람이 적어 넣는다.
 *
 * <p>이카운트는 「단위」 칸을 주지 않는다. 그렇다고 단위 없이 적재하면 나중에 BOX 와 EA 가
 * 섞인 원천이 붙었을 때 구분할 방법이 없다. 화면에서 「직접 입력」으로 채운 값이 모든 행에
 * 들어가야 한다.
 */
class TransformEngineConstantTest {

    private final TransformEngine engine = new TransformEngine();

    @Test
    @DisplayName("원천 칸 없이 적어 넣은 값이 모든 행에 들어간다")
    void 고정값이_모든_행에_들어간다() {
        List<TargetFieldSpec> specs = List.of(
                new TargetFieldSpec("item_ref", FieldDataType.STRING, true),
                new TargetFieldSpec("unit", FieldDataType.STRING, false));
        List<FieldMapping> mappings = List.of(
                FieldMapping.of("PROD_CD", "item_ref"),
                new FieldMapping("", "unit",
                        TransformRule.of(TransformType.CONSTANT, Map.of("value", "EA"))));

        MappedRow row = engine.map(new kr.suhsaechan.palim.connector.source.SourceRow(
                1, Map.of("PROD_CD", "A0001")), mappings, specs);

        assertThat(row.values().get("unit"))
                .as("원천에 없는 값을 사람이 적었으면 그대로 들어가야 한다")
                .isEqualTo("EA");
        assertThat(row.values().get("item_ref")).isEqualTo("A0001");
    }

    @Test
    @DisplayName("고정값은 원천 칸을 소비하지 않는다")
    void 고정값은_원천_칸을_쓰지_않는다() {
        List<TargetFieldSpec> specs = List.of(
                new TargetFieldSpec("item_ref", FieldDataType.STRING, true),
                new TargetFieldSpec("unit", FieldDataType.STRING, false));
        List<FieldMapping> mappings = List.of(
                FieldMapping.of("PROD_CD", "item_ref"),
                new FieldMapping("", "unit",
                        TransformRule.of(TransformType.CONSTANT, Map.of("value", "EA"))));

        MappedRow row = engine.map(new kr.suhsaechan.palim.connector.source.SourceRow(
                1, Map.of("PROD_CD", "A0001", "REMARK", "비고")), mappings, specs);

        assertThat(row.attributes())
                .as("연결하지 않은 원천 칸은 보존된다. 고정값이 그것을 가로채면 안 된다")
                .containsKey("REMARK");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :palim-connector:test --tests "*TransformEngineConstantTest*"`
Expected: FAIL — `TransformType.CONSTANT` 를 찾을 수 없음

> `TargetFieldSpec` 생성자 인자가 위와 다르면 실제 시그니처에 맞춘다. `MappedRow` 접근자 이름도 마찬가지다.

- [ ] **Step 3: enum 에 한 줄 추가**

`TransformType.java`:

```java
public enum TransformType {
    NONE,
    TRIM,
    UPPER,
    LOWER,
    NUMBER_STRIP,
    DATE_FORMAT,
    CODE_REPLACE,
    DEFAULT_IF_EMPTY,
    /**
     * 원천 칸 없이 <b>사람이 적어 넣은 값</b>을 모든 행에 넣는다.
     *
     * <p>{@code DEFAULT_IF_EMPTY} 와 다르다 — 그것은 원천 값이 비었을 때만 대신 쓰지만,
     * 이것은 원천에 <b>해당 칸 자체가 없을 때</b> 쓴다. 이카운트가 단위를 주지 않는 경우가 그렇다.
     */
    CONSTANT
}
```

- [ ] **Step 4: 원천 칸을 읽기 전에 분기**

`TransformEngine.java` 의 매핑 순회에서, `row.values().get(mapping.sourceField())` 를 호출하기 **전에** 고정값을 처리한다. 원천 칸 이름이 비어 있으므로 그대로 두면 `null` 을 읽는다.

```java
// 고정값은 원천을 보지 않는다. 칸 이름이 비어 있으므로 아래 조회를 그냥 태우면 null 이 된다.
if (mapping.rule().type() == TransformType.CONSTANT) {
    String constant = mapping.rule().param("value", "");
    values.put(mapping.targetFieldKey(), coerce(constant, spec, mapping.rule()));
    continue;
}

String raw = Objects.toString(row.values().get(mapping.sourceField()), "");
```

`applyRule` 의 switch 에도 분기를 더한다 (다른 경로로 들어와도 깨지지 않게):

```java
case CONSTANT -> rule.param("value", "");
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :palim-connector:test --tests "*TransformEngineConstantTest*"`
Expected: PASS 2건

- [ ] **Step 6: 전체 회귀**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL · 393건 (기존 391 + 신규 2)

- [ ] **Step 7: 커밋** — `/pro-commit`, 타입 `feat`, 설명 "원천에 없는 항목을 직접 입력으로 채우는 고정값 매핑 추가"

---

### Task 2: 인증을 검증에서 떼어낸다 (`EcountSessionClient`)

지역조회 → 로그인 → 조회 로직이 `ZoneSessionProbe` 안에 갇혀 있다. 그 클래스는 **연결 확인용**이라 단계별 성공/실패를 기록하는 것이 목적이고, 실행 경로에서 필요한 것은 자료뿐이다. 같은 인증을 두 벌 짜면 한쪽만 고쳐져 어긋난다.

**Files:**
- Create: `palim-connector/src/main/java/kr/suhsaechan/palim/connector/source/http/EcountSessionClient.java`
- Modify: `palim-connector/src/main/java/kr/suhsaechan/palim/connector/source/http/ZoneSessionProbe.java`
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/EcountSessionClientIntegrationTest.java`

**Interfaces:**
- Produces:
  - `String resolveZone(EcountEndpoint endpoint, String companyCode)` — 지역 코드. 없으면 `BusinessException(API_PROBE_FAILED)`
  - `String login(EcountEndpoint endpoint, String zone, String companyCode, String userId, String apiKey)` — 세션 ID
  - `List<Map<String, String>> fetchInventory(EcountEndpoint endpoint, String zone, String sessionId, LocalDate baseDate)` — 재고 행
  - `record EcountEndpoint(String domain, String sandboxPrefix, String livePrefix, boolean sandbox, String zoneUrlOverride, String apiBaseOverride)`
- Consumes: 없음 (독립)

- [ ] **Step 1: 실패하는 테스트 작성**

로컬 `HttpServer` 로 이카운트 응답 모양을 흉내 낸다. 실제 원격을 부르지 않는다 — 인증키가 1회용이라 테스트가 키를 태운다.

`palim-app/src/test/java/kr/suhsaechan/palim/integration/EcountSessionClientIntegrationTest.java`:

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.source.http.EcountSessionClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 인증과 조회가 <b>검증 화면 밖에서도</b> 동작하는가.
 *
 * <p>이 클라이언트가 없으면 연결 확인은 되는데 실제 수집이 안 된다 — 인증 절차가 검증용
 * 클래스 안에만 있어 실행 경로에서 쓸 수 없기 때문이다. 같은 절차를 두 벌 짜면 한쪽만
 * 고쳐져 어긋난다.
 */
class EcountSessionClientIntegrationTest extends IntegrationTest {

    @Autowired private EcountSessionClient client;

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String json;
            if (path.endsWith("/Zone")) {
                json = "{\"Data\":{\"ZONE\":\"AD\"}}";
            } else if (path.endsWith("/OAPILogin")) {
                json = "{\"Data\":{\"Datas\":{\"SESSION_ID\":\"sess-1\"}}}";
            } else {
                json = "{\"Data\":{\"Result\":[{\"PROD_CD\":\"A0001\",\"BAL_QTY\":\"112\"}]}}";
            }
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("지역 조회 → 로그인 → 재고 조회가 이어진다")
    void 인증부터_조회까지_이어진다() {
        String local = "http://127.0.0.1:" + server.getAddress().getPort();
        var endpoint = new EcountSessionClient.EcountEndpoint(
                "example.test", "sbo", "oapi", true, local + "/Zone", local);

        String zone = client.resolveZone(endpoint, "123456");
        assertThat(zone).isEqualTo("AD");

        String session = client.login(endpoint, zone, "123456", "tester", "dummy-key");
        assertThat(session).isEqualTo("sess-1");

        List<Map<String, String>> rows =
                client.fetchInventory(endpoint, zone, session, LocalDate.of(2026, 8, 13));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("PROD_CD", "A0001")
                .containsEntry("BAL_QTY", "112");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :palim-app:test --tests "*EcountSessionClientIntegrationTest*"`
Expected: FAIL — `EcountSessionClient` 없음

- [ ] **Step 3: 클라이언트 작성**

`ZoneSessionProbe` 에 있는 `post`·`extractRows`·`firstArray`·`toRows`·`text` 를 이 클래스로 옮긴다. **본문을 문자열로 직렬화해 보내는 방식을 반드시 유지한다** — `RestClient.builder()` 로 직접 만든 클라이언트에는 JSON 변환기가 붙지 않아 빈 본문이 조용히 나가고, 상대는 200 으로 "값 없음"을 돌려주므로 예외 없이 실패한다.

```java
@Component
public class EcountSessionClient {

    /** 접속 주소 조립에 필요한 것. 테스트·운영 접두어가 다르고, 통째로 덮어쓸 수도 있어야 한다. */
    public record EcountEndpoint(String domain, String sandboxPrefix, String livePrefix,
                                 boolean sandbox, String zoneUrlOverride,
                                 String apiBaseOverride) {

        public String zoneUrl() {
            return StringUtils.hasText(zoneUrlOverride) ? zoneUrlOverride
                    : "https://%s.%s/OAPI/V2/Zone".formatted(prefix(), domain);
        }

        public String apiBase(String zone) {
            return StringUtils.hasText(apiBaseOverride) ? apiBaseOverride
                    : "https://%s%s.%s/OAPI/V2".formatted(prefix(), zone, domain);
        }

        private String prefix() {
            return sandbox ? sandboxPrefix : livePrefix;
        }
    }
    // resolveZone · login · fetchInventory · post · extractRows …
}
```

실패는 `BusinessException(ErrorCode.API_PROBE_FAILED, 사유)` 로 던지고, 사유에는 **상대가 보낸 `Data.Message` 를 그대로** 넣는다.

- [ ] **Step 4: `ZoneSessionProbe` 가 이 클라이언트를 쓰게 바꾼다**

단계별 기록(`ProbeStep`)·`vendorReason`·`ipRegistrationHint` 는 `ZoneSessionProbe` 에 남긴다. 인증 절차만 클라이언트에 위임한다. **기존 검증 화면 동작이 바뀌면 안 된다.**

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :palim-app:test --tests "*EcountSessionClient*" --tests "*ApiProbe*"`
Expected: PASS — 신규 1건 + 기존 프로브 테스트 2건 그대로

- [ ] **Step 6: 전체 회귀 후 커밋**

Run: `./gradlew build` → `/pro-commit`, 타입 `refactor`, 설명 "이카운트 인증 절차를 검증 화면에서 분리해 수집 경로와 공유"

---

### Task 3: API 어댑터 (`HttpApiSourceReader`)

**이 하나가 없어서 매핑·시험 실행·자동 수집이 전부 막혀 있다.**

**Files:**
- Create: `palim-connector/src/main/java/kr/suhsaechan/palim/connector/source/HttpApiSourceReader.java`
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/HttpApiSourceReaderIntegrationTest.java`

**Interfaces:**
- Consumes: `EcountSessionClient` (Task 2)
- Produces: `SourceReader` 구현체 — `type()` 은 `SourceType.HTTP_API`. `SourceReaderRegistry` 가 자동으로 집는다

- [ ] **Step 1: 실패하는 테스트 작성**

`readSchema` 가 칸 목록과 샘플을 돌려주는지, `read` 가 전체 행을 흘리는지 확인한다. Task 2 와 같은 방식으로 로컬 서버를 띄운다.

```java
@Test
@DisplayName("API 원천에서 칸 목록과 샘플을 읽는다")
void 스키마를_읽는다() {
    SourceContext context = new SourceContext(connectorId, null, 1, null,
            Map.of("preset", "ECOUNT", "sandbox", "true",
                   "companyCode", "123456", "userId", "tester",
                   "zoneUrl", local + "/Zone", "apiBase", local));

    SourceSchema schema = reader.readSchema(context);

    assertThat(schema.fields()).contains("PROD_CD", "BAL_QTY");
    assertThat(schema.sampleRows()).isNotEmpty();
}
```

비밀값은 `SourceContext.config` 에 넣지 않는다. `connectorId` 로 `Connector.credentialRef` 를 찾아 `ConnectorSecretService` 에서 꺼낸다 — 테스트는 그 경로까지 통과해야 실제와 같다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :palim-app:test --tests "*HttpApiSourceReaderIntegrationTest*"`
Expected: FAIL — `HttpApiSourceReader` 없음

- [ ] **Step 3: 어댑터 작성**

```java
/**
 * REST 원천 어댑터.
 *
 * <p>{@code readSchema} 와 {@code read} 를 나눠 두는 이유가 여기서 드러난다 — 매핑 화면은
 * 칸 이름과 샘플 몇 행이면 되고, 적재는 전체를 흘려야 한다. 하나로 합치면 화면을 열 때마다
 * 전체를 받는다.
 *
 * <p>비밀값은 {@link SourceContext} 에 담지 않는다. 그 값은 설정으로 저장되어 화면에서
 * 조회되므로, 인증키가 섞이면 목록 화면 한 번에 유출된다. 커넥터의 {@code credentialRef} 로
 * 그때그때 꺼낸다.
 */
@Component
@RequiredArgsConstructor
public class HttpApiSourceReader implements SourceReader {

    private static final int SAMPLE_LIMIT = 5;

    private final EcountSessionClient ecount;
    private final ConnectorRepository connectorRepository;
    private final ConnectorSecretService secrets;

    @Override
    public SourceType type() {
        return SourceType.HTTP_API;
    }

    @Override
    public SourceSchema readSchema(SourceContext context) {
        List<Map<String, String>> rows = fetch(context);
        List<String> fields = rows.isEmpty() ? List.of()
                : List.copyOf(rows.getFirst().keySet());
        List<Map<String, Object>> samples = rows.stream()
                .limit(SAMPLE_LIMIT)
                .map(row -> (Map<String, Object>) new LinkedHashMap<String, Object>(row))
                .toList();
        return new SourceSchema(fields, samples, rows.size());
    }

    @Override
    public Stream<SourceRow> read(SourceContext context) {
        List<Map<String, String>> rows = fetch(context);
        return IntStream.range(0, rows.size())
                .mapToObj(i -> new SourceRow(i + 1, new LinkedHashMap<>(rows.get(i))));
    }
}
```

`fetch` 는 프리셋을 보고 갈라진다. 지금은 `ECOUNT` 만 있고, 폼 로그인(3PL)은 Task 10 에서 더한다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :palim-app:test --tests "*HttpApiSourceReaderIntegrationTest*"`
Expected: PASS

- [ ] **Step 5: 레지스트리가 집는지 확인**

`SourceReaderRegistry.of(SourceType.HTTP_API)` 가 예외를 던지지 않아야 한다. 스프링이 구현체를 주입하므로 별도 등록 코드는 없다.

- [ ] **Step 6: 전체 회귀 후 커밋** — 타입 `feat`, 설명 "API 원천에서 칸 목록과 자료를 읽는 어댑터 추가"

---

### Task 4: 파일 없이 실행

매핑 화면의 시험 실행·실제 적재가 파일 업로드를 강제한다. API 커넥터는 올릴 파일이 없다.

**Files:**
- Modify: `palim-web/src/main/java/kr/suhsaechan/palim/web/mapping/MappingController.java`
- Modify: `palim-web/src/main/resources/templates/connector/mapping.html`
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/ApiConnectorRunIntegrationTest.java`

**Interfaces:**
- Consumes: `HttpApiSourceReader` (Task 3)

- [ ] **Step 1: 실패하는 테스트** — 파일 없이 `RunRequest` 를 만들어 `ConnectorRunner` 가 스테이징에 행을 넣는지 확인

- [ ] **Step 2: 실패 확인** → **Step 3: 컨트롤러에서 파일 파라미터를 선택으로** → **Step 4: 화면에서 API 커넥터면 파일칸 대신 「지금 가져와서 실행」** → **Step 5: 통과 확인** → **Step 6: 커밋**

화면 분기는 `connector.sourceType == HTTP_API` 로 판단한다. 업로드 커넥터의 기존 동작은 그대로 둔다.

---

### Task 5: 자동 추천 — 사전 · 이름 규칙 · 값 모양

**Files:**
- Modify: `palim-connector/.../model/FieldDefinition.java` (별칭)
- Modify: `palim-connector/.../model/StockSnapshotFields.java` (별칭 등록)
- Create: `palim-connector/.../suggest/FieldSuggester.java` · `SuggestionSource.java` · `FieldSuggestion.java` · `AliasSource.java` · `NamePatternSource.java` · `ValueShapeSource.java`
- Test: `palim-connector/src/test/java/.../suggest/FieldSuggesterTest.java`

**Interfaces:**
- Produces: `List<FieldSuggestion> suggest(List<String> sourceFields, List<Map<String,Object>> samples, String targetModel)`
- `record FieldSuggestion(String sourceField, String targetFieldKey, int score, List<String> reasons)`

- [ ] **Step 1: 실패하는 테스트**

```java
@Test
@DisplayName("사전에 적어 둔 이름이 가장 높은 점수를 받는다")
void 사전이_최고점을_받는다() {
    List<FieldSuggestion> result = suggester.suggest(
            List.of("BAL_QTY", "PROD_CD"),
            List.of(Map.of("BAL_QTY", "112", "PROD_CD", "A0001")),
            "std_stock_snapshot");

    assertThat(pick(result, "BAL_QTY").targetFieldKey()).isEqualTo("quantity");
    assertThat(pick(result, "PROD_CD").targetFieldKey()).isEqualTo("item_ref");
}

@Test
@DisplayName("근거가 약하면 고르지 않는다")
void 근거가_약하면_비운다() {
    List<FieldSuggestion> result = suggester.suggest(
            List.of("COL_017"), List.of(Map.of("COL_017", "알 수 없는 값")),
            "std_stock_snapshot");

    assertThat(result).as("찍어서 틀리느니 비워 두는 편이 낫다").isEmpty();
}
```

- [ ] **Step 2: 실패 확인** → **Step 3: `FieldDefinition` 에 별칭 오버로드 추가**

```java
public record FieldDefinition(String key, String displayName, FieldDataType dataType,
                              boolean required, List<String> aliases) {

    /** 별칭 없이 쓰던 기존 호출부가 그대로 컴파일되게 남긴다. */
    public static FieldDefinition required(String key, String displayName, FieldDataType type) {
        return new FieldDefinition(key, displayName, type, true, List.of());
    }

    public static FieldDefinition required(String key, String displayName, FieldDataType type,
                                           String... aliases) {
        return new FieldDefinition(key, displayName, type, true, List.of(aliases));
    }
    // optional 도 같은 꼴로
}
```

- [ ] **Step 4: 재고 모델에 별칭 등록**

```java
required("item_ref", "품목", STRING, "PROD_CD", "ITEM_CD", "SKU", "품목코드", "제품코드"),
required("quantity", "수량", DECIMAL, "BAL_QTY", "QTY", "STOCK_QTY", "수량", "재고수량"),
optional("warehouse_code", "창고 코드", STRING, "WH_CD", "WAREHOUSE_CD", "창고코드"),
optional("warehouse_name", "창고명", STRING, "WH_DES", "WAREHOUSE_NM", "창고명"),
optional("raw_item_name", "원본 품명", STRING, "PROD_DES", "ITEM_NM", "품명"),
optional("unit", "단위", STRING, "UNIT", "UOM", "단위"),
```

- [ ] **Step 5: 근거 셋 구현**

`AliasSource` 100점 (대소문자·공백·밑줄 무시하고 비교) · `NamePatternSource` 60점 · `ValueShapeSource` 40점. 임계값 미만이면 결과에서 뺀다.

- [ ] **Step 6: 통과 확인 후 커밋** — 타입 `feat`, 설명 "칸 연결 자동 추천 — 사전·이름 규칙·값 모양"

---

### Task 6: 자동 추천 — 예전 기록

**우리가 모르는 시스템도 한 번만 손대면 그 뒤로 자동이 된다.** 네 근거 중 확장성의 핵심이다.

**Files:**
- Create: `palim-app/src/main/resources/db/migration/V19__field_mapping_memory.sql`
- Create: `palim-connector/.../suggest/HistorySource.java` · `FieldMappingMemory.java` · `FieldMappingMemoryRepository.java`
- Modify: 매핑 확정 지점 (`MappingAdminService`) — 확정 시 기록
- Test: `palim-app/src/test/java/.../FieldMappingMemoryIntegrationTest.java`

- [ ] **Step 1: 실패하는 테스트** — 기록이 쌓이면 사전에 없는 이름도 추천되는가 · 확정 시에만 기록되는가 · 테넌트가 다르면 안 보이는가

- [ ] **Step 2: 실패 확인** → **Step 3: 마이그레이션**

```sql
-- 한 번 연결한 것을 기억한다. 우리가 모르는 시스템도 두 번째부터는 추천된다.
CREATE TABLE field_mapping_memory
(
    id           uuid         NOT NULL,
    tenant_id    uuid         NOT NULL,
    -- 대소문자·공백·밑줄을 정규화해 저장한다. BAL_QTY 와 bal qty 는 같은 이름이다.
    source_field varchar(200) NOT NULL,
    target_model varchar(100) NOT NULL,
    target_field varchar(100) NOT NULL,
    hit_count    integer      NOT NULL DEFAULT 1,
    last_used_at timestamptz  NOT NULL,
    created_at   timestamptz,
    updated_at   timestamptz,
    CONSTRAINT pk_field_mapping_memory PRIMARY KEY (id)
);
-- PG14 라 NULLS NOT DISTINCT 를 쓸 수 없다. 모든 컬럼이 NOT NULL 이므로 평범한 유니크로 충분하다.
CREATE UNIQUE INDEX ux_field_mapping_memory
    ON field_mapping_memory (tenant_id, source_field, target_model, target_field);
```

- [ ] **Step 4: `HistorySource` 90점** — `hit_count` 가 많을수록 가산. `INSERT ... ON CONFLICT ... DO UPDATE SET hit_count = field_mapping_memory.hit_count + 1` 로 올린다 (`MERGE` 금지)

- [ ] **Step 5: 확정 시에만 기록** — 화면에서 고르는 중에는 기록하지 않는다. 고민하며 눌러 본 것까지 학습하면 기억이 오염된다

- [ ] **Step 6: 통과 확인 후 커밋** — 타입 `feat`, 설명 "한 번 연결한 칸을 기억해 다음 연동에서 먼저 추천"

---

### Task 7: 칸 연결 화면 재작성

**Files:**
- Modify: `palim-web/src/main/resources/templates/connector/mapping.html`
- Modify: `palim-web/src/main/java/kr/suhsaechan/palim/web/mapping/MappingController.java`
- Create: `palim-web/src/main/java/kr/suhsaechan/palim/web/mapping/MappingRowView.java` · `MappingGroupView.java`
- Delete 대상: `palim-web/.../mock/` (시안 페이지 — 진짜 화면이 생기면 지운다. **사용자 확인 후**)

- [ ] **Step 1: 뷰 모델** — 시안(`MappingMockController.MockRow`)의 모양을 그대로 쓴다: `label · required · mode(SELECT/AUTO/CONSTANT/NONE) · picked · constant · preview · warning`

- [ ] **Step 2: 컨트롤러** — `readSchema()` 로 칸+샘플을 받고, `FieldSuggester` 로 미리 고르고, 표준 항목을 성격별로 묶어 넘긴다. `source`·`base_at` 은 `AUTO` 로 고정

- [ ] **Step 3: 화면** — daisyUI 만 쓴다(07-DECISIONS 009). 「우리 항목 / 저쪽 칸 / 들어올 값」 세 칸. 표준에 없는 원천 칸은 별도 구역에 값과 함께

- [ ] **Step 4: 파일 업로드 단계 제거** — API 커넥터면 `readSchema` 가 칸을 주므로 1단계가 필요 없다

- [ ] **Step 5: 화면 확인 후 커밋** — 타입 `feat`

---

### Task 8: 시험 실행 뷰어 재작성

**Files:**
- Modify: `palim-web/src/main/resources/templates/connector/run-detail.html`
- Modify: `palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorQueryService.java`
- Create: `palim-web/src/main/java/kr/suhsaechan/palim/web/connector/StagingRowView.java`

- [ ] **Step 1: `payload` JSON 을 표로 펼친다** — 지금은 원문을 그대로 뿌려 읽을 수 없다
- [ ] **Step 2: 「진짜 자료에는 아직 안 들어갔습니다」를 맨 위에** — 이걸 모르면 확인을 대충 한다
- [ ] **Step 3: 실패 줄에 이유를 붙인다** — 건수만 알려주면 손댈 데가 없다
- [ ] **Step 4: 「저쪽이 보낸 값 그대로 보기」 접이식** — 변환 실패는 원본을 봐야 원인이 보인다
- [ ] **Step 5: 커밋** — 타입 `feat`

---

### Task 9: 스케줄러

**Files:**
- Create: `palim-connector/src/main/java/kr/suhsaechan/palim/connector/run/ConnectorScheduler.java`
- Test: `palim-app/src/test/java/.../ConnectorSchedulerIntegrationTest.java`

- [ ] **Step 1: 실패하는 테스트** — 확정된 매핑이 있는 API 커넥터만 실행 대상이 되는가 · 확정 전이면 건너뛰는가
- [ ] **Step 2~4: `Connector.scheduleCron` 을 읽어 주기 실행**. 실패는 `ConnectorRun` 에 남기고 연속 실패는 목록 화면에 드러낸다 — 매일 도는 일은 잘 돌 때 아무도 보지 않으므로, 멈췄을 때 말해주지 않으면 몇 주가 지나간다
- [ ] **Step 5: 커밋** — 타입 `feat`

---

### Task 10: 3PL(폼 로그인) 원천 붙이기

대조는 두 곳을 비교하는 일이라 **한쪽만으로는 시작할 수 없다.**

- [ ] `HttpApiSourceReader.fetch` 에 `FORM_SESSION` 분기 추가 — 로그인 폼 전송 → 세션 쿠키 → 조회
- [ ] 상대 화면이 바뀌면 깨지므로 수집 실패를 반드시 드러낸다. 조용히 멈추면 옛 자료로 대조가 계속 돌고 그 결과를 믿게 된다

---

## Self-Review

**1. 설계 문서 대응** — API 어댑터(T3) · 인증 분리(T2) · 고정값(T1) · 칸 연결 화면(T7) · 시험 뷰어(T8) · 파일 없는 실행(T4) · 스케줄(T9) · 자동 추천(T5·T6) 전부 대응됨. 설계 문서의 "다루지 않는 것"(SQL 직접 입력 · 조인 · DB 직결)은 계획에도 없다.

**2. 미완 항목** — Task 4·7·8·9·10 은 단계 서술이 T1~T3 보다 성기다. 앞 작업이 끝나야 실제 시그니처가 확정되므로, **각 작업 착수 시점에 그 작업만 상세화**한다. 착수 전 상세화 없이 바로 구현하지 않는다.

**3. 타입 일관성** — `FieldSuggestion`(T5)은 T7 화면이 소비한다. `EcountEndpoint`(T2)는 T3 이 소비한다. `TransformType.CONSTANT`(T1)는 T7 「직접 입력」이 저장한다.

**4. 순서** — T1→T2→T3 이 뼈대이고 T4 부터는 T3 에 의존한다. T5·T6 은 T7 이전에 끝나야 화면이 추천을 받을 수 있다.
