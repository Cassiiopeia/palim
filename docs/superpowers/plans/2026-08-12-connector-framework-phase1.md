# 범용 데이터 연동 프레임워크 Phase 1 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 엑셀 파일을 업로드하면 화면에서 정의한 매핑대로 변환되어 표준 모델 테이블에 적재되는 파이프라인을 만든다. 화면·AI·cron 없이 서비스 계층과 통합 테스트로 완결된다.

**Architecture:** 새 모듈 `palim-connector`가 연동 엔진을 담는다. 도메인을 모른다. 원천(`SourceReader`) → 매핑/변환(`TransformEngine`) → 적재(`RecordWriter`)의 3단 파이프라인이고, TEST 실행은 스테이징 테이블에만 쓰고 LIVE 실행만 표준 테이블에 UPSERT 한다.

**Tech Stack:** Java 25 · Spring Boot 4.1 · PostgreSQL(Flyway) · JPA + JdbcClient · Python 3(pandas) 서브프로세스 · Testcontainers

## Global Constraints

프로젝트 전역 규칙이다. **모든 태스크의 요구사항에 암묵적으로 포함된다.**

- 시각은 전 계층 `Instant`, DB `timestamptz`. `LocalDateTime` 금지 (표시 직전 변환만)
- `JdbcClient` 바인딩에는 `Instant` 대신 **`OffsetDateTime`**. `count`·`sum` 은 `bigint` 이므로 `record` 가 `int` 면 `count(*)::int` 캐스팅
- 예외는 `BusinessException` + `ErrorCode` 만. **새 예외 클래스 금지**. 새 실패 유형은 `ErrorCode` enum 한 줄 + `errors.properties`/`errors_en.properties` 각 한 줄
- 동결 도메인(`palim-sku`·`palim-order`·`palim-collector`·`palim-channel`·`palim-mapping`·`palim-incident`) **수정 금지**. 테이블명 충돌도 피한다
- py 호출은 `ProcessBuilder(List.of(...))` 인자 배열. stdout JSON only, stderr 는 사람용. `PYTHONIOENCODING=utf-8` + Java 읽기 UTF-8 명시. `waitFor(타임아웃)` + `destroyForcibly()`. 전용 스레드풀(크기 2)
- 통합 테스트는 `IntegrationTest` 상속(Testcontainers 실제 PostgreSQL). 인메모리 DB 금지
- 엔티티는 `BaseTimeEntity` 상속 + `UuidV7.generate()` + `@NoArgsConstructor(access = PROTECTED)` + 정적 팩토리 `of()`. **record 컴포넌트와 같은 이름의 정적 팩토리 금지**
- Spring Boot 4: Jackson 은 `tools.jackson.databind`/`core`, 애노테이션만 `com.fasterxml.jackson.annotation`. 예외는 unchecked `JacksonException`
- 커밋 메시지에 AI 흔적 금지 (`Co-Authored-By`·`Generated with`·🤖). CI `guard` 잡이 실패시킨다
- 발주사 상호·브랜드·제품명·계정명·채널 URL 을 코드·문서·테스트에 쓰지 않는다. 테스트는 합성 데이터로
- 커밋 메시지 형식: `범용 데이터 연동 프레임워크와 물품 표준 모델 : {타입} : {설명} https://github.com/Cassiiopeia/palim/issues/53`

**로컬 빌드**: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew ...`

---

## File Structure

```
palim-connector/
  build.gradle.kts
  src/main/java/kr/suhsaechan/palim/connector/
    model/          TargetModel · TargetField · TargetModelKind · TargetStorage · FieldDataType
    define/         Connector · ConnectorMapping · ConnectorFieldMap · MappingStatus · SourceType
    unit/           UnitConversion · UnitConverter
    source/         SourceReader · SourceSchema · SourceRow · SourceContext · UploadSourceReader
    excel/          ExcelParser · ExcelParseResult · ScriptProperties(재사용)
    schema/         SchemaSnapshot · DriftDetector · DriftVerdict
    transform/      TransformEngine · TransformRule · TransformType · MappedRow
    key/            NaturalKeyBuilder
    write/          RecordWriter · StagingWriter · StandardModelWriter · WriteResult
    run/            ConnectorRun · ConnectorRunError · ConnectorStaging · RunMode · RunTrigger
                    ConnectorRunner · RunLock
  src/test/java/... 각 단위 테스트

palim-app/src/main/resources/db/migration/
  V15__connector_framework.sql      정의·실행 계층
  V16__standard_models.sql          std_item · std_stock_snapshot · std_stock_movement · std_outbound_order

palim-common/src/main/java/.../error/ErrorCode.java     K 접두사 14개 추가
palim-common/src/main/resources/errors.properties       메시지
palim-common/src/main/resources/errors_en.properties    메시지

scripts/parse_stock_excel.py                            엑셀 → JSON

palim-app/src/test/java/.../integration/
  ConnectorPipelineIntegrationTest.java                 E2E
```

---

## Task 1: 모듈 골격과 ErrorCode

**Files:**
- Create: `palim-connector/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `palim-automation/build.gradle.kts`
- Modify: `palim-common/src/main/java/kr/suhsaechan/palim/common/error/ErrorCode.java`
- Modify: `palim-common/src/main/resources/errors.properties`
- Modify: `palim-common/src/main/resources/errors_en.properties`

**Interfaces:**
- Produces: `ErrorCode.CONNECTOR_NOT_FOUND` 외 13개. 이후 모든 태스크가 이 코드로 실패를 표현한다

- [ ] **Step 1: 모듈 등록**

`settings.gradle.kts` 의 `// 공통` 블록 아래, `include("palim-automation")` 앞에 추가:

```kotlin
// 연동 — 외부 데이터를 표준 모델로 들이는 범용 엔진. 도메인을 모른다.
include("palim-connector")
```

`palim-connector/build.gradle.kts` 생성:

```kotlin
plugins {
    `java-library`
}

dependencies {
    // palim-common 이 spring-boot-starter-data-jpa 와 starter-json 을 api 로 노출한다.
    api(project(":palim-common"))

    // HTTP 원천 어댑터(RestClient). web starter 는 쓰지 않는다 — 클라이언트만 필요하다.
    implementation("org.springframework:spring-web")

    testImplementation(testFixtures(project(":palim-common")))
}
```

`palim-automation/build.gradle.kts` 의 `dependencies` 에 추가:

```kotlin
    // 표준 모델 위에 도메인 기능을 얹는다. 연동 엔진은 도메인을 모르므로 방향은 한쪽뿐이다.
    api(project(":palim-connector"))
```

- [ ] **Step 2: 빌드가 통과하는지 확인**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-connector:compileJava`
Expected: BUILD SUCCESSFUL (소스가 없어도 성공한다)

- [ ] **Step 3: ErrorCode 추가**

`ErrorCode.java` 의 마지막 항목 `AI_DAILY_LIMIT_EXCEEDED("X006", ...)` 의 세미콜론을 쉼표로 바꾸고 뒤에 추가한다. 접두사 `K` 는 기존에 쓰이지 않는다(사용 중: A C H I M N O S X Y).

```java
    /** 커넥터를 찾을 수 없다. */
    CONNECTOR_NOT_FOUND("K001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    /** 같은 커넥터가 이미 실행 중이다. cron 과 수동 실행이 겹치는 순간은 반드시 온다. */
    CONNECTOR_ALREADY_RUNNING("K002", HttpStatus.CONFLICT, LogLevel.WARN),

    /** 원천에 접근할 수 없다(파일 없음·API 응답 없음). */
    CONNECTOR_SOURCE_UNREACHABLE("K003", HttpStatus.BAD_GATEWAY, LogLevel.ERROR),

    /** LIVE 실행은 ACTIVE 매핑에서만 가능하다. */
    MAPPING_NOT_ACTIVE("K004", HttpStatus.CONFLICT, LogLevel.WARN),

    /** 매핑 버전을 찾을 수 없다. */
    MAPPING_NOT_FOUND("K005", HttpStatus.NOT_FOUND, LogLevel.WARN),

    /** 원천 양식이 바뀌었다. 조용히 잘못된 데이터가 들어가는 것을 막는다. */
    SCHEMA_DRIFT_DETECTED("K006", HttpStatus.CONFLICT, LogLevel.ERROR),

    /** 필수 목표 필드에 값이 없다. */
    REQUIRED_FIELD_MISSING("K007", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.DEBUG),

    /** 값을 목표 필드 타입으로 변환할 수 없다. */
    FIELD_TYPE_MISMATCH("K008", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.DEBUG),

    /** 단위가 명시됐는데 환산 규칙이 없다. 조용히 1:1 로 넘기면 수량이 둔갑한다. */
    UNIT_CONVERSION_NOT_FOUND("K009", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.WARN),

    /** py 훅 실행이 실패했다. */
    HOOK_EXECUTION_FAILED("K010", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    /** py 훅이 타임아웃됐다. */
    HOOK_TIMEOUT("K011", HttpStatus.GATEWAY_TIMEOUT, LogLevel.ERROR),

    /** 사용 중인 목표 모델은 삭제할 수 없다. */
    TARGET_MODEL_IN_USE("K012", HttpStatus.CONFLICT, LogLevel.WARN),

    /** 자연키 구성 필드가 비어 UPSERT 대상을 특정할 수 없다. */
    NATURAL_KEY_INCOMPLETE("K013", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.WARN),

    /** 마지막 LIVE 실행이 아닌 것은 되돌릴 수 없다. */
    ROLLBACK_NOT_ALLOWED("K014", HttpStatus.CONFLICT, LogLevel.WARN);
```

`errors.properties` 끝에 추가:

```properties
error.CONNECTOR_NOT_FOUND=커넥터를 찾을 수 없습니다.
error.CONNECTOR_ALREADY_RUNNING=이미 실행 중입니다. 완료 후 다시 시도하세요.
error.CONNECTOR_SOURCE_UNREACHABLE=원천에 접근할 수 없습니다: {0}
error.MAPPING_NOT_ACTIVE=확정되지 않은 매핑으로는 실제 적재를 할 수 없습니다. 테스트 실행만 가능합니다.
error.MAPPING_NOT_FOUND=매핑 버전을 찾을 수 없습니다.
error.SCHEMA_DRIFT_DETECTED=원천 양식이 바뀌었습니다: {0}. 매핑을 다시 확정해야 합니다.
error.REQUIRED_FIELD_MISSING=필수 항목이 비어 있습니다: {0}
error.FIELD_TYPE_MISMATCH={0} 항목의 값 "{1}" 을 {2} 형식으로 읽을 수 없습니다.
error.UNIT_CONVERSION_NOT_FOUND={0} 단위를 {1} 로 환산하는 규칙이 없습니다.
error.HOOK_EXECUTION_FAILED=후처리 스크립트 실행에 실패했습니다: {0}
error.HOOK_TIMEOUT=후처리 스크립트가 시간 내에 끝나지 않았습니다.
error.TARGET_MODEL_IN_USE=사용 중인 모델은 삭제할 수 없습니다. 연결된 커넥터: {0}
error.NATURAL_KEY_INCOMPLETE=중복 판정 기준 항목이 비어 있습니다: {0}
error.ROLLBACK_NOT_ALLOWED=가장 최근 실행만 되돌릴 수 있습니다.
```

`errors_en.properties` 끝에 추가:

```properties
error.CONNECTOR_NOT_FOUND=Connector not found.
error.CONNECTOR_ALREADY_RUNNING=Already running. Try again after it finishes.
error.CONNECTOR_SOURCE_UNREACHABLE=Cannot reach the source: {0}
error.MAPPING_NOT_ACTIVE=A draft mapping cannot be used for a live run. Test runs only.
error.MAPPING_NOT_FOUND=Mapping version not found.
error.SCHEMA_DRIFT_DETECTED=The source format changed: {0}. Confirm the mapping again.
error.REQUIRED_FIELD_MISSING=Required field is empty: {0}
error.FIELD_TYPE_MISMATCH=Cannot read "{1}" as {2} for field {0}.
error.UNIT_CONVERSION_NOT_FOUND=No rule to convert {0} to {1}.
error.HOOK_EXECUTION_FAILED=Post-processing script failed: {0}
error.HOOK_TIMEOUT=Post-processing script timed out.
error.TARGET_MODEL_IN_USE=Model is in use. Connected connectors: {0}
error.NATURAL_KEY_INCOMPLETE=Deduplication key fields are empty: {0}
error.ROLLBACK_NOT_ALLOWED=Only the most recent run can be rolled back.
```

- [ ] **Step 4: 기존 ErrorCode 검증 테스트로 확인**

`ErrorCodeIntegrationTest` 가 `values()` 를 순회해 코드 중복과 메시지 누락을 자동 검사한다. 새 코드를 추가했으므로 이 테스트가 곧 검증이다.

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-app:test --tests '*ErrorCodeIntegrationTest'`
Expected: PASS (코드 중복 없음, ko/en 메시지 모두 존재)

- [ ] **Step 5: 커밋**

```bash
git add settings.gradle.kts palim-connector/build.gradle.kts palim-automation/build.gradle.kts \
        palim-common/src/main/java/kr/suhsaechan/palim/common/error/ErrorCode.java \
        palim-common/src/main/resources/errors.properties \
        palim-common/src/main/resources/errors_en.properties
git commit -m "범용 데이터 연동 프레임워크와 물품 표준 모델 : feat : palim-connector 모듈 골격과 커넥터 에러코드 추가 https://github.com/Cassiiopeia/palim/issues/53"
```

---

## Task 2: 정의 계층 스키마와 엔티티

**Files:**
- Create: `palim-app/src/main/resources/db/migration/V15__connector_framework.sql`
- Create: `palim-connector/src/main/java/kr/suhsaechan/palim/connector/model/TargetModel.java`
- Create: `.../model/TargetField.java` · `TargetModelKind.java` · `TargetStorage.java` · `FieldDataType.java`
- Create: `.../model/TargetModelRepository.java` · `TargetFieldRepository.java`
- Create: `.../define/Connector.java` · `ConnectorMapping.java` · `ConnectorFieldMap.java` · `MappingStatus.java` · `SourceType.java` · `IncrementalMode.java`
- Create: `.../define/ConnectorRepository.java` · `ConnectorMappingRepository.java` · `ConnectorFieldMapRepository.java`
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/ConnectorDefinitionIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 의 `ErrorCode`
- Produces:
  - `TargetModel.of(UUID tenantId, String code, String name, TargetModelKind kind, TargetStorage storage, String tableName, List<String> naturalKeyFields)`
  - `TargetField.of(UUID tenantId, UUID modelId, String fieldKey, String displayName, FieldDataType dataType, boolean required, String defaultValue, int sortOrder)`
  - `Connector.of(UUID tenantId, String code, String name, UUID targetModelId, SourceType sourceType)`
  - `ConnectorMapping.draft(UUID tenantId, UUID connectorId, int version, String sourceSchemaJson)`
  - `ConnectorMapping.activate()` · `ConnectorFieldMap.of(UUID tenantId, UUID mappingId, String sourceField, String targetFieldKey, String transformRuleJson, int sortOrder)`

- [ ] **Step 1: 마이그레이션 작성**

`V15__connector_framework.sql`:

```sql
-- ============================================================
-- 범용 데이터 연동 프레임워크 — 정의·실행 계층 (#53)
--
-- 연동 정의를 코드가 아니라 데이터로 둔다. 새 원천이 붙어도 배포가 필요 없고,
-- 원천 양식이 바뀌면 매핑 버전을 올린다.
--
-- 모든 테이블에 tenant_id 를 둔다. 지금은 기본 테넌트 하나로 운영하지만,
-- 나중에 넣으려면 전 테이블 컬럼 추가 + 전 쿼리 수정 + 데이터 소급이 필요하다.
-- ============================================================

-- 기본 테넌트. 멀티테넌시는 문만 열어둔 상태다.
CREATE TABLE tenant
(
    id         uuid         NOT NULL,
    code       varchar(50)  NOT NULL,
    name       varchar(100) NOT NULL,
    created_at timestamptz,
    updated_at timestamptz,
    CONSTRAINT pk_tenant PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_tenant_code ON tenant (code);

INSERT INTO tenant (id, code, name, created_at, updated_at)
VALUES ('00000000-0000-7000-8000-000000000001', 'default', '기본', now(), now());

-- 목표 모델. BUILTIN 은 정식 테이블에, CUSTOM 은 custom_record JSONB 에 적재된다.
CREATE TABLE target_model
(
    id                 uuid         NOT NULL,
    tenant_id          uuid         NOT NULL,
    code               varchar(50)  NOT NULL,
    name               varchar(100) NOT NULL,
    kind               varchar(20)  NOT NULL,
    storage            varchar(20)  NOT NULL,
    -- BUILTIN 일 때만 채운다. 적재 직전에 이 이름으로 갈라진다.
    table_name         varchar(63),
    -- 무엇이 같으면 같은 행인가. UPSERT 의 기준이며 비어 있으면 재실행이 중복을 만든다.
    natural_key_fields jsonb        NOT NULL DEFAULT '[]'::jsonb,
    created_at         timestamptz,
    updated_at         timestamptz,
    CONSTRAINT pk_target_model PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_target_model_code ON target_model (tenant_id, code);

CREATE TABLE target_field
(
    id              uuid         NOT NULL,
    tenant_id       uuid         NOT NULL,
    target_model_id uuid         NOT NULL,
    field_key       varchar(63)  NOT NULL,
    display_name    varchar(100) NOT NULL,
    data_type       varchar(20)  NOT NULL,
    required        boolean      NOT NULL DEFAULT false,
    default_value   varchar(255),
    sort_order      integer      NOT NULL DEFAULT 0,
    created_at      timestamptz,
    updated_at      timestamptz,
    CONSTRAINT pk_target_field PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_target_field_key ON target_field (target_model_id, field_key);
CREATE INDEX ix_target_field_model ON target_field (target_model_id, sort_order);

-- 연동 하나. 원천 접속 정보의 비밀값은 여기 두지 않고 참조만 남긴다.
CREATE TABLE connector
(
    id               uuid         NOT NULL,
    tenant_id        uuid         NOT NULL,
    code             varchar(50)  NOT NULL,
    name             varchar(100) NOT NULL,
    target_model_id  uuid         NOT NULL,
    source_type      varchar(20)  NOT NULL,
    -- HTTP_API 일 때의 비민감 설정(URL·응답 경로 등). 키·비밀번호는 넣지 않는다.
    source_config    jsonb        NOT NULL DEFAULT '{}'::jsonb,
    -- 암호화 저장소의 자격증명 식별자. 값 자체는 여기 없다.
    credential_ref   varchar(100),
    -- 단위가 비어 있는 원천의 기본 단위. 실측한 두 원천 모두 단위 컬럼이 없다.
    default_unit     varchar(20)  NOT NULL DEFAULT 'EA',
    incremental_mode varchar(20)  NOT NULL DEFAULT 'FULL',
    cursor_field     varchar(63),
    cursor_value     varchar(255),
    schedule_cron    varchar(100),
    enabled          boolean      NOT NULL DEFAULT true,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_connector PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_connector_code ON connector (tenant_id, code);
CREATE INDEX ix_connector_model ON connector (target_model_id);

-- 매핑 버전. 실행 기록이 버전을 참조하므로 정의를 바꿔도 과거를 설명할 수 있다.
CREATE TABLE connector_mapping
(
    id                  uuid        NOT NULL,
    tenant_id           uuid        NOT NULL,
    connector_id        uuid        NOT NULL,
    version             integer     NOT NULL,
    status              varchar(20) NOT NULL,
    -- 확정 당시의 원천 필드 목록. 매 실행마다 이것과 대조해 드리프트를 잡는다.
    source_schema       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- py 훅 정의. 규칙으로 안 되는 커스텀만 여기 온다.
    hooks               jsonb       NOT NULL DEFAULT '[]'::jsonb,
    activated_at        timestamptz,
    created_at          timestamptz,
    updated_at          timestamptz,
    CONSTRAINT pk_connector_mapping PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_connector_mapping_version ON connector_mapping (connector_id, version);
-- 커넥터당 ACTIVE 는 하나뿐이다. 부분 유니크 인덱스로 DB 가 보장한다.
CREATE UNIQUE INDEX ux_connector_mapping_active
    ON connector_mapping (connector_id) WHERE status = 'ACTIVE';

CREATE TABLE connector_field_map
(
    id               uuid        NOT NULL,
    tenant_id        uuid        NOT NULL,
    mapping_id       uuid        NOT NULL,
    source_field     varchar(255) NOT NULL,
    target_field_key varchar(63) NOT NULL,
    -- 값 변환 규칙. {"type":"DATE_FORMAT","pattern":"yyyy-MM-dd"} 형태.
    transform_rule   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    sort_order       integer     NOT NULL DEFAULT 0,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_connector_field_map PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_connector_field_map_target ON connector_field_map (mapping_id, target_field_key);
CREATE INDEX ix_connector_field_map_mapping ON connector_field_map (mapping_id, sort_order);

-- 단위 환산. item_ref 가 NULL 이면 전역 규칙이다.
CREATE TABLE unit_conversion
(
    id         uuid           NOT NULL,
    tenant_id  uuid           NOT NULL,
    item_ref   varchar(255),
    from_unit  varchar(20)    NOT NULL,
    to_unit    varchar(20)    NOT NULL,
    factor     numeric(19, 6) NOT NULL,
    created_at timestamptz,
    updated_at timestamptz,
    CONSTRAINT pk_unit_conversion PRIMARY KEY (id)
);
-- item_ref 가 NULL 인 전역 규칙과 품목별 규칙이 공존한다. NULLS NOT DISTINCT 로
-- 전역 규칙의 중복도 막는다(PostgreSQL 15+).
CREATE UNIQUE INDEX ux_unit_conversion
    ON unit_conversion (tenant_id, item_ref, from_unit, to_unit) NULLS NOT DISTINCT;
```

- [ ] **Step 2: 마이그레이션이 적용되는지 확인**

`ConnectorDefinitionIntegrationTest` 를 만들어 기본 테넌트 행이 존재하는지 확인한다.

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 연동 정의 계층 스키마 검증. */
class ConnectorDefinitionIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @DisplayName("기본 테넌트가 마이그레이션으로 생성된다")
    void 기본_테넌트가_생성된다() {
        int count = jdbcClient.sql("SELECT count(*)::int FROM tenant WHERE code = 'default'")
                .query(Integer.class).single();

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("커넥터당 ACTIVE 매핑은 하나뿐이다")
    void ACTIVE_매핑은_하나뿐이다() {
        // 부분 유니크 인덱스가 존재하는지로 검증한다. 두 번째 ACTIVE 삽입은 DB 가 막는다.
        int indexCount = jdbcClient.sql("""
                        SELECT count(*)::int FROM pg_indexes
                        WHERE tablename = 'connector_mapping'
                          AND indexname = 'ux_connector_mapping_active'
                        """)
                .query(Integer.class).single();

        assertThat(indexCount).isEqualTo(1);
    }
}
```

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-app:test --tests '*ConnectorDefinitionIntegrationTest'`
Expected: FAIL — 테이블이 없다 (마이그레이션 파일을 아직 안 만들었다면). 만든 뒤엔 PASS

- [ ] **Step 3: enum 4종 작성**

```java
// TargetModelKind.java
package kr.suhsaechan.palim.connector.model;

/** 목표 모델의 성격. BUILTIN 위에만 도메인 기능을 미리 만들 수 있다. */
public enum TargetModelKind { BUILTIN, CUSTOM }
```

```java
// TargetStorage.java
package kr.suhsaechan.palim.connector.model;

/** 적재 대상. 파이프라인은 저장 직전에만 이 값으로 갈라진다. */
public enum TargetStorage { TABLE, JSONB }
```

```java
// FieldDataType.java
package kr.suhsaechan.palim.connector.model;

/**
 * 목표 필드의 값 타입.
 *
 * <p>배열·중첩 객체는 두지 않는다. 타입을 넓히면 검증·변환·화면 입력기가 배수로 늘어나는데,
 * 그런 필드가 필요한 사례가 아직 없다. 필요하면 {@code attributes} 에 JSON 으로 넣는다.
 */
public enum FieldDataType { STRING, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP }
```

```java
// MappingStatus.java
package kr.suhsaechan.palim.connector.define;

/**
 * 매핑 버전의 상태.
 *
 * <p>{@code DRAFT} 로도 <b>테스트 실행은 가능하다</b> — 그것이 확정 전 검증의 목적이다.
 * 실제 적재(LIVE)만 {@code ACTIVE} 를 요구한다.
 */
public enum MappingStatus { DRAFT, ACTIVE, ARCHIVED }
```

```java
// SourceType.java
package kr.suhsaechan.palim.connector.define;

/** 원천 유형. 구현체를 추가해도 파이프라인 뒷단은 바뀌지 않는다. */
public enum SourceType { UPLOAD, HTTP_API }
```

```java
// IncrementalMode.java
package kr.suhsaechan.palim.connector.define;

/** 수집 방식. INCREMENTAL 은 성공한 실행만 커서를 전진시킨다. */
public enum IncrementalMode { FULL, INCREMENTAL }
```

- [ ] **Step 4: 엔티티 작성**

`TargetModel` 을 예로 든다. 나머지 엔티티도 같은 골격(`BaseTimeEntity` 상속 · `UuidV7` · protected 기본 생성자 · 정적 팩토리)을 따르며, 필드는 Step 1 의 컬럼과 1:1 로 대응한다.

```java
package kr.suhsaechan.palim.connector.model;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 목표 모델 — 데이터를 어디에 담을 것인가.
 *
 * <p>{@code naturalKeyFields} 가 이 엔티티의 핵심이다. "무엇이 같으면 같은 행인가"를 정의하며,
 * 이 값이 비어 있으면 재실행이 중복 행을 만든다. 재시도가 안전해야 사람이 자동화를 켠다.
 */
@Getter
@Entity
@Table(name = "target_model")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TargetModel extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TargetModelKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TargetStorage storage;

    /** BUILTIN 일 때만 값이 있다. */
    @Column(length = 63)
    private String tableName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> naturalKeyFields;

    private TargetModel(UUID tenantId, String code, String name, TargetModelKind kind,
                        TargetStorage storage, String tableName, List<String> naturalKeyFields) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.storage = storage;
        this.tableName = tableName;
        this.naturalKeyFields = naturalKeyFields;
    }

    public static TargetModel of(UUID tenantId, String code, String name, TargetModelKind kind,
                                 TargetStorage storage, String tableName,
                                 List<String> naturalKeyFields) {
        return new TargetModel(tenantId, code, name, kind, storage, tableName, naturalKeyFields);
    }

    /** 커스텀 모델은 JSONB 로만 저장한다. 런타임 DDL 을 쓰지 않기 때문이다. */
    public boolean isCustom() {
        return kind == TargetModelKind.CUSTOM;
    }
}
```

나머지 엔티티의 필드 구성:

- **`TargetField`**: `id` · `tenantId` · `targetModelId` · `fieldKey` · `displayName` ·
  `dataType`(enum) · `required`(boolean) · `defaultValue` · `sortOrder`
- **`Connector`**: `id` · `tenantId` · `code` · `name` · `targetModelId` · `sourceType`(enum) ·
  `sourceConfig`(JSON `Map<String,Object>`) · `credentialRef` · `defaultUnit` ·
  `incrementalMode`(enum) · `cursorField` · `cursorValue` · `scheduleCron` · `enabled`
  - 메서드: `advanceCursor(String value)` — 성공한 실행만 호출한다
- **`ConnectorMapping`**: `id` · `tenantId` · `connectorId` · `version` · `status`(enum) ·
  `sourceSchema`(JSON) · `hooks`(JSON `List<Map<String,Object>>`) · `activatedAt`
  - 팩토리: `draft(...)`, 메서드: `activate()`(status→ACTIVE, activatedAt=Instant.now()),
    `archive()`
- **`ConnectorFieldMap`**: `id` · `tenantId` · `mappingId` · `sourceField` · `targetFieldKey` ·
  `transformRule`(JSON `Map<String,Object>`) · `sortOrder`

- [ ] **Step 5: 리포지토리 작성**

```java
package kr.suhsaechan.palim.connector.define;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorMappingRepository extends JpaRepository<ConnectorMapping, UUID> {

    Optional<ConnectorMapping> findByConnectorIdAndStatus(UUID connectorId, MappingStatus status);

    List<ConnectorMapping> findByConnectorIdOrderByVersionDesc(UUID connectorId);
}
```

`TargetModelRepository`(`findByTenantIdAndCode`), `TargetFieldRepository`
(`findByTargetModelIdOrderBySortOrder`), `ConnectorRepository`(`findByTenantIdAndCode`,
`findByEnabledTrue`), `ConnectorFieldMapRepository`(`findByMappingIdOrderBySortOrder`) 도
같은 형태로 만든다.

- [ ] **Step 6: 테스트 실행**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-app:test --tests '*ConnectorDefinitionIntegrationTest'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add palim-app/src/main/resources/db/migration/V15__connector_framework.sql \
        palim-connector/src/main/java palim-app/src/test/java/kr/suhsaechan/palim/integration/ConnectorDefinitionIntegrationTest.java
git commit -m "범용 데이터 연동 프레임워크와 물품 표준 모델 : feat : 연동 정의 계층 스키마와 엔티티 추가 https://github.com/Cassiiopeia/palim/issues/53"
```

---

## Task 3: 표준 모델 스키마

**Files:**
- Create: `palim-app/src/main/resources/db/migration/V16__standard_models.sql`
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/StandardModelSchemaIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2 의 `tenant` 테이블
- Produces: `std_item` · `std_stock_snapshot` · `std_stock_movement` · `std_outbound_order` 테이블과 각 자연키 유니크 인덱스. Task 11 의 `StandardModelWriter` 가 이 이름으로 UPSERT 한다

- [ ] **Step 1: 마이그레이션 작성**

`V16__standard_models.sql`:

```sql
-- ============================================================
-- 물품 표준 모델 (#53)
--
-- 물품이면 무엇이든 담기도록 성격이 다른 셋으로 나눈다.
--   품목  변하지 않는 것    재고  시점별 상태    이동  사건
--
-- std_ 접두사를 붙인 이유 — 동결 도메인(palim-sku)에 이미 stock_movement 가 있다.
-- 동결 도메인은 수정하지 않으므로 우리 쪽 이름을 구분한다.
--
-- 빈 칸을 허용한다. 식품은 expiry_date, 전자제품은 serial_no, 원자재는 lot_code 를
-- 쓰므로 셋 다 두고 업종을 가리지 않게 한다.
-- ============================================================

CREATE TABLE std_item
(
    id                 uuid         NOT NULL,
    tenant_id          uuid         NOT NULL,
    run_id             uuid,
    -- 식별
    item_code          varchar(100) NOT NULL,
    item_name          varchar(255) NOT NULL,
    barcode            varchar(100),
    external_id        varchar(100),
    spec               varchar(255),
    option_name        varchar(255),
    -- 분류
    category_code      varchar(50),
    category_name      varchar(100),
    brand              varchar(100),
    manufacturer       varchar(100),
    origin_country     varchar(50),
    -- 공급. 같은 물건을 공급처가 다르게 부르는 일은 업종을 가리지 않는다.
    supplier_code      varchar(50),
    supplier_name      varchar(100),
    supplier_item_code varchar(100),
    supplier_item_name varchar(255),
    -- 단위
    base_unit          varchar(20),
    pack_size          integer,
    weight             numeric(19, 3),
    volume             numeric(19, 3),
    -- 금액
    standard_cost      numeric(19, 2),
    sale_price         numeric(19, 2),
    currency           varchar(3),
    -- 상태
    is_active          boolean      NOT NULL DEFAULT true,
    discontinued_at    timestamptz,
    -- 표준에 없는 원천 컬럼을 버리지 않는다. 과거 시점 데이터는 다시 받을 수 없다.
    attributes         jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at         timestamptz,
    updated_at         timestamptz,
    CONSTRAINT pk_std_item PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_std_item_natural ON std_item (tenant_id, item_code);
CREATE INDEX ix_std_item_run ON std_item (run_id);

CREATE TABLE std_stock_snapshot
(
    id                 uuid         NOT NULL,
    tenant_id          uuid         NOT NULL,
    run_id             uuid,
    item_ref           varchar(255) NOT NULL,
    -- 시점. 두 원천을 다른 시각에 뽑으면 그 사이 출고분만큼 무조건 차이가 난다.
    base_at            timestamptz  NOT NULL,
    source             varchar(50)  NOT NULL,
    collected_at       timestamptz,
    -- 위치
    warehouse_code     varchar(50),
    warehouse_name     varchar(100),
    location_code      varchar(50),
    zone_code          varchar(50),
    -- 로트
    lot_code           varchar(100),
    expiry_date        date,
    manufacture_date   date,
    serial_no          varchar(100),
    -- 수량. 집계는 base_quantity 만 쓴다.
    quantity           numeric(19, 3) NOT NULL,
    unit               varchar(20),
    base_quantity      numeric(19, 3) NOT NULL,
    base_unit          varchar(20)  NOT NULL,
    available_quantity numeric(19, 3),
    reserved_quantity  numeric(19, 3),
    defective_quantity numeric(19, 3),
    incoming_quantity  numeric(19, 3),
    outgoing_quantity  numeric(19, 3),
    -- 금액
    unit_cost          numeric(19, 2),
    amount             numeric(19, 2),
    currency           varchar(3),
    quality_status     varchar(20),
    -- 원본을 버리지 않는다. 정규화 규칙을 바꿔도 재계산할 수 있다.
    raw_item_name      varchar(255),
    normalized_name    varchar(255),
    product_key        varchar(255),
    attributes         jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at         timestamptz,
    updated_at         timestamptz,
    CONSTRAINT pk_std_stock_snapshot PRIMARY KEY (id)
);
-- 자연키. 같은 구간을 두 번 가져와도 중복이 생기지 않는다.
CREATE UNIQUE INDEX ux_std_stock_snapshot_natural
    ON std_stock_snapshot (tenant_id, source, base_at, item_ref, warehouse_code, lot_code)
    NULLS NOT DISTINCT;
CREATE INDEX ix_std_stock_snapshot_run ON std_stock_snapshot (run_id);
CREATE INDEX ix_std_stock_snapshot_lookup
    ON std_stock_snapshot (tenant_id, base_at DESC, source);
CREATE INDEX ix_std_stock_snapshot_product ON std_stock_snapshot (tenant_id, product_key);

CREATE TABLE std_stock_movement
(
    id             uuid           NOT NULL,
    tenant_id      uuid           NOT NULL,
    run_id         uuid,
    item_ref       varchar(255)   NOT NULL,
    occurred_at    timestamptz    NOT NULL,
    movement_type  varchar(20)    NOT NULL,
    reason_code    varchar(50),
    quantity       numeric(19, 3) NOT NULL,
    unit           varchar(20),
    base_quantity  numeric(19, 3) NOT NULL,
    base_unit      varchar(20)    NOT NULL,
    from_warehouse varchar(50),
    to_warehouse   varchar(50),
    from_location  varchar(50),
    to_location    varchar(50),
    lot_code       varchar(100),
    expiry_date    date,
    document_no    varchar(100),
    document_name  varchar(255),
    reference_no   varchar(100),
    operator       varchar(100),
    attributes     jsonb          NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz,
    updated_at     timestamptz,
    CONSTRAINT pk_std_stock_movement PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_std_stock_movement_natural
    ON std_stock_movement (tenant_id, document_no, item_ref, occurred_at, lot_code)
    NULLS NOT DISTINCT;
CREATE INDEX ix_std_stock_movement_run ON std_stock_movement (run_id);
CREATE INDEX ix_std_stock_movement_lookup
    ON std_stock_movement (tenant_id, occurred_at DESC, item_ref);

-- 개인정보를 담는 유일한 표준 모델이다. 보존기간·마스킹·접근권한을 여기에만 걸 수 있도록
-- 다른 모델과 분리했다.
CREATE TABLE std_outbound_order
(
    id               uuid           NOT NULL,
    tenant_id        uuid           NOT NULL,
    run_id           uuid,
    order_no         varchar(100)   NOT NULL,
    order_line_no    integer,
    ordered_at       timestamptz,
    channel          varchar(100),
    item_ref         varchar(255)   NOT NULL,
    quantity         numeric(19, 3) NOT NULL,
    unit_price       numeric(19, 2),
    receiver_name    varchar(100),
    receiver_phone   varchar(50),
    receiver_address varchar(500),
    postal_code      varchar(20),
    delivery_memo    varchar(500),
    carrier          varchar(50),
    tracking_no      varchar(100),
    status           varchar(20),
    shipped_at       timestamptz,
    attributes       jsonb          NOT NULL DEFAULT '{}'::jsonb,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_std_outbound_order PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_std_outbound_order_natural
    ON std_outbound_order (tenant_id, order_no, order_line_no, item_ref) NULLS NOT DISTINCT;
CREATE INDEX ix_std_outbound_order_run ON std_outbound_order (run_id);

-- 표준 모델 4종을 target_model 에 등록한다. 화면이 이 목록에서 고른다.
INSERT INTO target_model (id, tenant_id, code, name, kind, storage, table_name,
                          natural_key_fields, created_at, updated_at)
VALUES
    ('00000000-0000-7000-8000-000000000010', '00000000-0000-7000-8000-000000000001',
     'std_item', '품목', 'BUILTIN', 'TABLE', 'std_item',
     '["item_code"]'::jsonb, now(), now()),
    ('00000000-0000-7000-8000-000000000011', '00000000-0000-7000-8000-000000000001',
     'std_stock_snapshot', '재고 스냅샷', 'BUILTIN', 'TABLE', 'std_stock_snapshot',
     '["source","base_at","item_ref","warehouse_code","lot_code"]'::jsonb, now(), now()),
    ('00000000-0000-7000-8000-000000000012', '00000000-0000-7000-8000-000000000001',
     'std_stock_movement', '입출고 이력', 'BUILTIN', 'TABLE', 'std_stock_movement',
     '["document_no","item_ref","occurred_at","lot_code"]'::jsonb, now(), now()),
    ('00000000-0000-7000-8000-000000000013', '00000000-0000-7000-8000-000000000001',
     'std_outbound_order', '출고 주문', 'BUILTIN', 'TABLE', 'std_outbound_order',
     '["order_no","order_line_no","item_ref"]'::jsonb, now(), now());
```

> **`target_field` 초기 데이터**는 이 마이그레이션에 넣지 않는다. 필드가 100개 가까이 되어
> SQL 이 길어지고, 화면 표시명·순서가 자주 바뀐다. Task 12 에서 `ConfigDefinitionProvider`
> 와 같은 방식의 **부트스트랩 컴포넌트**로 등록한다 — 없는 필드만 자동 추가되고 코드 옆에서
> 관리된다.

- [ ] **Step 2: 검증 테스트 작성**

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 표준 모델 스키마 검증. */
class StandardModelSchemaIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @DisplayName("동결 도메인의 stock_movement 와 표준 모델이 공존한다")
    void 동결_도메인과_이름이_충돌하지_않는다() {
        int frozen = tableCount("stock_movement");
        int standard = tableCount("std_stock_movement");

        assertThat(frozen).as("동결 도메인 테이블은 그대로 있어야 한다").isEqualTo(1);
        assertThat(standard).as("표준 모델 테이블이 따로 생겨야 한다").isEqualTo(1);
    }

    @Test
    @DisplayName("표준 모델 4종이 target_model 에 등록된다")
    void 표준_모델이_등록된다() {
        int count = jdbcClient.sql(
                        "SELECT count(*)::int FROM target_model WHERE kind = 'BUILTIN'")
                .query(Integer.class).single();

        assertThat(count).isEqualTo(4);
    }

    @Test
    @DisplayName("재고 스냅샷 자연키가 NULL 을 같은 값으로 취급한다")
    void 자연키가_NULL_을_구분하지_않는다() {
        // lot_code 가 NULL 인 두 행을 넣으면 두 번째가 막혀야 한다.
        // NULLS NOT DISTINCT 가 없으면 NULL != NULL 이라 중복이 생긴다.
        String indexDef = jdbcClient.sql("""
                        SELECT indexdef FROM pg_indexes
                        WHERE indexname = 'ux_std_stock_snapshot_natural'
                        """)
                .query(String.class).single();

        assertThat(indexDef).contains("NULLS NOT DISTINCT");
    }

    private int tableCount(String name) {
        return jdbcClient.sql("SELECT count(*)::int FROM pg_tables WHERE tablename = :name")
                .param("name", name)
                .query(Integer.class).single();
    }
}
```

- [ ] **Step 3: 테스트 실행**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-app:test --tests '*StandardModelSchemaIntegrationTest'`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add palim-app/src/main/resources/db/migration/V16__standard_models.sql \
        palim-app/src/test/java/kr/suhsaechan/palim/integration/StandardModelSchemaIntegrationTest.java
git commit -m "범용 데이터 연동 프레임워크와 물품 표준 모델 : feat : 물품 표준 모델 4종 스키마 추가 https://github.com/Cassiiopeia/palim/issues/53"
```

---

## Task 4: 실행 계층 스키마와 엔티티

**Files:**
- Modify: `palim-app/src/main/resources/db/migration/V15__connector_framework.sql` (아직 배포 전이므로 같은 파일에 이어 쓴다)
- Create: `.../run/ConnectorRun.java` · `ConnectorRunError.java` · `ConnectorStaging.java` · `RunMode.java` · `RunTrigger.java` · `RunStatus.java`
- Create: `.../run/ConnectorRunRepository.java` · `ConnectorRunErrorRepository.java` · `ConnectorStagingRepository.java`
- Create: `.../model/CustomRecord.java` · `CustomRecordRepository.java`

**Interfaces:**
- Consumes: Task 2 의 `Connector` · `ConnectorMapping`
- Produces:
  - `ConnectorRun.start(UUID tenantId, UUID connectorId, UUID mappingId, int mappingVersion, RunMode mode, RunTrigger trigger)`
  - `ConnectorRun.finish(int total, int success, int failed)` · `ConnectorRun.fail(String summary)`
  - `ConnectorRunError.of(UUID tenantId, UUID runId, int rowNumber, Map<String,Object> sourceRow, String errorCode, String message)`
  - `ConnectorStaging.of(UUID tenantId, UUID runId, int rowNumber, String naturalKey, Map<String,Object> payload)`

- [ ] **Step 1: 마이그레이션에 실행 계층 추가**

`V15__connector_framework.sql` 끝에 이어 쓴다:

```sql
-- ------------------------------------------------------------
-- 실행 계층
-- ------------------------------------------------------------

CREATE TABLE connector_run
(
    id              uuid        NOT NULL,
    tenant_id       uuid        NOT NULL,
    connector_id    uuid        NOT NULL,
    mapping_id      uuid        NOT NULL,
    -- 정의를 바꿔도 과거 실행이 어느 버전으로 돌았는지 남는다.
    mapping_version integer     NOT NULL,
    run_mode        varchar(10) NOT NULL,
    trigger_type    varchar(20) NOT NULL,
    status          varchar(20) NOT NULL,
    total_count     integer     NOT NULL DEFAULT 0,
    success_count   integer     NOT NULL DEFAULT 0,
    failed_count    integer     NOT NULL DEFAULT 0,
    error_summary   varchar(1000),
    started_at      timestamptz NOT NULL,
    finished_at     timestamptz,
    created_at      timestamptz,
    updated_at      timestamptz,
    CONSTRAINT pk_connector_run PRIMARY KEY (id)
);
CREATE INDEX ix_connector_run_connector
    ON connector_run (connector_id, started_at DESC);
-- 동시 실행 차단. RUNNING 은 커넥터당 하나뿐이다.
CREATE UNIQUE INDEX ux_connector_run_running
    ON connector_run (connector_id) WHERE status = 'RUNNING';

-- 실패한 행을 원본째 보존한다. 화면에서 그 행만 보고 고칠 수 있다.
-- 보존기간 제한과 정리 배치는 두지 않는다(설계 9-6, 오너 판단).
CREATE TABLE connector_run_error
(
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    run_id     uuid        NOT NULL,
    row_number integer     NOT NULL,
    source_row jsonb       NOT NULL,
    error_code varchar(50) NOT NULL,
    message    varchar(1000),
    created_at timestamptz,
    updated_at timestamptz,
    CONSTRAINT pk_connector_run_error PRIMARY KEY (id)
);
CREATE INDEX ix_connector_run_error_run ON connector_run_error (run_id, row_number);

-- TEST 실행 결과. 운영 테이블에 닿지 않으므로 부담 없이 테스트할 수 있다.
CREATE TABLE connector_staging
(
    id          uuid         NOT NULL,
    tenant_id   uuid         NOT NULL,
    run_id      uuid         NOT NULL,
    row_number  integer      NOT NULL,
    natural_key varchar(500) NOT NULL,
    payload     jsonb        NOT NULL,
    created_at  timestamptz,
    updated_at  timestamptz,
    CONSTRAINT pk_connector_staging PRIMARY KEY (id)
);
CREATE INDEX ix_connector_staging_run ON connector_staging (run_id, row_number);

-- 커스텀 모델 데이터. 런타임 DDL 없이 모델이 늘어도 테이블 수는 그대로다.
CREATE TABLE custom_record
(
    id              uuid         NOT NULL,
    tenant_id       uuid         NOT NULL,
    target_model_id uuid         NOT NULL,
    run_id          uuid,
    -- 자연키가 없으면 UPSERT 자체가 성립하지 않는다.
    natural_key     varchar(500) NOT NULL,
    payload         jsonb        NOT NULL,
    created_at      timestamptz,
    updated_at      timestamptz,
    CONSTRAINT pk_custom_record PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_custom_record_natural
    ON custom_record (tenant_id, target_model_id, natural_key);
CREATE INDEX ix_custom_record_run ON custom_record (run_id);
CREATE INDEX ix_custom_record_payload ON custom_record USING gin (payload);

-- LIVE 되돌리기용. UPSERT 직전 값을 남긴다. 가장 최근 실행 하나만 되돌릴 수 있다.
CREATE TABLE connector_undo_log
(
    id             uuid         NOT NULL,
    tenant_id      uuid         NOT NULL,
    run_id         uuid         NOT NULL,
    table_name     varchar(63)  NOT NULL,
    natural_key    varchar(500) NOT NULL,
    -- NULL 이면 그 행은 이번 실행이 처음 만든 것이므로 되돌리기는 삭제다.
    previous_row   jsonb,
    created_at     timestamptz,
    updated_at     timestamptz,
    CONSTRAINT pk_connector_undo_log PRIMARY KEY (id)
);
CREATE INDEX ix_connector_undo_log_run ON connector_undo_log (run_id);
```

- [ ] **Step 2: enum 작성**

```java
// RunMode.java
package kr.suhsaechan.palim.connector.run;

/**
 * 실행 모드.
 *
 * <p>{@code TEST} 는 {@code connector_staging} 에만 쓴다. 운영 테이블에 닿지 않으므로
 * 지우기 전에 도메인 로직이 읽어 오염된 결과를 내는 일이 없다.
 */
public enum RunMode { TEST, LIVE }
```

```java
// RunTrigger.java
package kr.suhsaechan.palim.connector.run;

public enum RunTrigger { MANUAL, SCHEDULED }
```

```java
// RunStatus.java
package kr.suhsaechan.palim.connector.run;

public enum RunStatus { RUNNING, SUCCEEDED, PARTIAL, FAILED, ROLLED_BACK }
```

- [ ] **Step 3: `ConnectorRun` 엔티티 작성**

```java
package kr.suhsaechan.palim.connector.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 실행 1건.
 *
 * <p>{@code mappingVersion} 을 값으로 박아 둔다. 정의를 나중에 바꿔도 "지난달 데이터가 왜
 * 이런가"에 답할 수 있어야 하기 때문이다. 매핑을 참조만 하면 정의가 바뀌는 순간 과거를
 * 설명할 방법이 사라진다.
 */
@Getter
@Entity
@Table(name = "connector_run")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorRun extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID connectorId;

    @Column(nullable = false)
    private UUID mappingId;

    @Column(nullable = false)
    private int mappingVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RunMode runMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private RunTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status;

    @Column(nullable = false)
    private int totalCount;

    @Column(nullable = false)
    private int successCount;

    @Column(nullable = false)
    private int failedCount;

    @Column(length = 1000)
    private String errorSummary;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    private ConnectorRun(UUID tenantId, UUID connectorId, UUID mappingId, int mappingVersion,
                         RunMode runMode, RunTrigger triggerType) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.connectorId = connectorId;
        this.mappingId = mappingId;
        this.mappingVersion = mappingVersion;
        this.runMode = runMode;
        this.triggerType = triggerType;
        this.status = RunStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public static ConnectorRun start(UUID tenantId, UUID connectorId, UUID mappingId,
                                     int mappingVersion, RunMode runMode, RunTrigger triggerType) {
        return new ConnectorRun(tenantId, connectorId, mappingId, mappingVersion,
                runMode, triggerType);
    }

    /**
     * 실행 종료.
     *
     * <p>실패 행이 하나라도 있으면 {@code PARTIAL} 이다. 성공으로 표시하면 사람이 실패 행을
     * 보지 않게 되고, 실패로 표시하면 성공분까지 버린 것으로 오해한다.
     */
    public void finish(int total, int success, int failed) {
        this.totalCount = total;
        this.successCount = success;
        this.failedCount = failed;
        this.status = failed == 0 ? RunStatus.SUCCEEDED : RunStatus.PARTIAL;
        this.finishedAt = Instant.now();
    }

    public void fail(String summary) {
        this.status = RunStatus.FAILED;
        this.errorSummary = summary;
        this.finishedAt = Instant.now();
    }

    public void markRolledBack() {
        this.status = RunStatus.ROLLED_BACK;
    }

    public boolean isTest() {
        return runMode == RunMode.TEST;
    }
}
```

`ConnectorRunError` · `ConnectorStaging` · `CustomRecord` 는 같은 골격으로 만든다. JSONB
필드는 `@JdbcTypeCode(SqlTypes.JSON) private Map<String, Object> payload;` 로 매핑한다.

- [ ] **Step 4: 테스트 — 실행 상태 전이 확인**

`palim-connector/src/test/java/kr/suhsaechan/palim/connector/run/ConnectorRunTest.java`:

```java
package kr.suhsaechan.palim.connector.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectorRunTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    @DisplayName("실패 행이 하나도 없으면 SUCCEEDED 다")
    void 전부_성공하면_SUCCEEDED() {
        ConnectorRun run = newRun();

        run.finish(100, 100, 0);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("실패 행이 하나라도 있으면 PARTIAL 이다")
    void 일부_실패하면_PARTIAL() {
        ConnectorRun run = newRun();

        run.finish(100, 97, 3);

        assertThat(run.getStatus()).isEqualTo(RunStatus.PARTIAL);
        assertThat(run.getSuccessCount()).isEqualTo(97);
        assertThat(run.getFailedCount()).isEqualTo(3);
    }

    private ConnectorRun newRun() {
        return ConnectorRun.start(TENANT, UUID.randomUUID(), UUID.randomUUID(), 1,
                RunMode.TEST, RunTrigger.MANUAL);
    }
}
```

- [ ] **Step 5: 테스트 실행**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-connector:test`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add palim-app/src/main/resources/db/migration/V15__connector_framework.sql palim-connector/src
git commit -m "범용 데이터 연동 프레임워크와 물품 표준 모델 : feat : 실행 계층 스키마와 엔티티 추가 https://github.com/Cassiiopeia/palim/issues/53"
```

---

## Task 5: 엑셀 파서 py 스크립트

**Files:**
- Create: `scripts/parse_stock_excel.py`
- Test: 스크립트 단독 실행 (수동 검증 단계 포함)

**Interfaces:**
- Produces: stdout JSON `{"fields":["헤더1",...],"rows":[{"헤더1":"값",...},...],"row_count":N}`.
  Task 6 의 `ExcelParser` 가 이 형식을 파싱한다

- [ ] **Step 1: 스크립트 작성**

```python
#!/usr/bin/env python3
"""엑셀/CSV 를 JSON 으로 변환한다.

호출 규약(04-CONVENTIONS):
  - stdout 에는 JSON 만. 사람용 메시지는 stderr.
  - 종료코드 0(성공) / 1(실패).
  - 인자는 배열로 받는다. 쉘 문자열을 조립하지 않는다.

사용:
  python3 parse_stock_excel.py <파일경로> [--header-row N] [--limit N] [--sheet NAME]

--limit 은 미리보기용이다. 전체 적재 시에는 지정하지 않는다.
"""

import argparse
import json
import sys

import pandas as pd


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("path")
    parser.add_argument("--header-row", type=int, default=1,
                        help="헤더가 있는 행 번호(1부터). 기본 1")
    parser.add_argument("--limit", type=int, default=0,
                        help="0이면 전체. 미리보기는 5 정도")
    parser.add_argument("--sheet", default=None)
    args = parser.parse_args()

    try:
        # header 는 0부터라 1을 뺀다. 그 위의 행은 제목·설명이므로 건너뛴다.
        header_index = max(args.header_row - 1, 0)

        if args.path.lower().endswith(".csv"):
            frame = pd.read_csv(args.path, header=header_index, dtype=str,
                                keep_default_na=False)
        else:
            frame = pd.read_excel(args.path, header=header_index, dtype=str,
                                  keep_default_na=False,
                                  sheet_name=args.sheet if args.sheet else 0)
    except FileNotFoundError:
        print(f"파일을 찾을 수 없습니다: {args.path}", file=sys.stderr)
        return 1
    except Exception as exc:  # 파싱 실패는 종류가 많아 하나로 묶는다
        print(f"파일을 읽을 수 없습니다: {exc}", file=sys.stderr)
        return 1

    # 컬럼명 공백 정리. 원본 헤더에 줄바꿈·앞뒤 공백이 섞여 있는 경우가 흔하다.
    frame.columns = [str(c).strip().replace("\n", " ") for c in frame.columns]

    # 이름 없는 컬럼(Unnamed: 3 등)은 빈 열이므로 버린다.
    frame = frame.loc[:, [not str(c).startswith("Unnamed:") for c in frame.columns]]

    total = len(frame)
    if args.limit > 0:
        frame = frame.head(args.limit)

    rows = frame.to_dict(orient="records")

    json.dump(
        {"fields": list(frame.columns), "rows": rows, "row_count": total},
        sys.stdout, ensure_ascii=False,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: 합성 데이터로 단독 실행 확인**

발주사 실데이터를 쓰지 않는다. 합성 CSV 를 만들어 검증한다.

```bash
cat > /tmp/palim-sample.csv <<'EOF'
품목코드,품목명,재고수량,재고단가
A-001,샘플품목 100g (27.01.01),120,1500
A-002,샘플품목 200g (27.02.01),0,2900
EOF

python3 scripts/parse_stock_excel.py /tmp/palim-sample.csv
```

Expected: 한 줄 JSON. `fields` 에 4개 헤더, `rows` 에 2건, `row_count` 가 2.
stderr 에는 아무것도 없어야 한다.

- [ ] **Step 3: 미리보기 제한 확인**

```bash
python3 scripts/parse_stock_excel.py /tmp/palim-sample.csv --limit 1
```

Expected: `rows` 는 1건이지만 `row_count` 는 **2**. 전체 건수는 제한과 무관하게 알려야
화면이 "2건 중 1건 미리보기"를 표시할 수 있다.

- [ ] **Step 4: 실패 경로 확인**

```bash
python3 scripts/parse_stock_excel.py /tmp/does-not-exist.csv; echo "exit=$?"
```

Expected: stdout 은 비어 있고, stderr 에 한국어 메시지, `exit=1`.

- [ ] **Step 5: 커밋**

```bash
git add scripts/parse_stock_excel.py
git commit -m "범용 데이터 연동 프레임워크와 물품 표준 모델 : feat : 엑셀 파싱 스크립트 추가 https://github.com/Cassiiopeia/palim/issues/53"
```

---

## Task 6: 엑셀 파서 Java 어댑터

**Files:**
- Create: `.../excel/ExcelParser.java` · `ExcelParseResult.java` · `ConnectorScriptProperties.java`
- Create: `.../source/SourceSchema.java` · `SourceRow.java` · `SourceContext.java` · `SourceReader.java` · `UploadSourceReader.java`
- Test: `palim-connector/src/test/java/.../excel/ExcelParserTest.java`

**Interfaces:**
- Consumes: Task 5 의 스크립트 출력 형식
- Produces:
  - `record ExcelParseResult(List<String> fields, List<Map<String,Object>> rows, int rowCount)`
  - `ExcelParser.parse(Path file, int headerRow, int limit)` → `ExcelParseResult`
  - `record SourceSchema(List<String> fields, List<Map<String,Object>> sampleRows, int totalCount)`
  - `record SourceRow(int rowNumber, Map<String,Object> values)`
  - `SourceReader.readSchema(SourceContext)` · `SourceReader.read(SourceContext)`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.suhsaechan.palim.connector.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 엑셀 파서 어댑터.
 *
 * <p>py 스크립트를 실제로 실행한다. 모킹하면 규약 위반(stdout 에 사람용 메시지가 섞이는 등)을
 * 잡지 못하는데, 그것이 이 계층에서 가장 자주 깨지는 지점이다.
 */
class ExcelParserTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("CSV 헤더와 행을 읽는다")
    void CSV_를_읽는다() throws IOException {
        Path csv = tempDir.resolve("sample.csv");
        Files.writeString(csv, """
                품목코드,품목명,재고수량
                A-001,샘플품목 100g (27.01.01),120
                A-002,샘플품목 200g (27.02.01),0
                """, StandardCharsets.UTF_8);

        ExcelParser parser = new ExcelParser(new ConnectorScriptProperties("scripts", "python3", 60));

        ExcelParseResult result = parser.parse(csv, 1, 0);

        assertThat(result.fields()).containsExactly("품목코드", "품목명", "재고수량");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().getFirst().get("품목명"))
                .isEqualTo("샘플품목 100g (27.01.01)");
        assertThat(result.rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("미리보기 제한을 걸어도 전체 건수는 그대로 알려준다")
    void 미리보기는_전체_건수를_보존한다() throws IOException {
        Path csv = tempDir.resolve("sample.csv");
        Files.writeString(csv, """
                코드,이름
                A,가
                B,나
                C,다
                """, StandardCharsets.UTF_8);

        ExcelParser parser = new ExcelParser(new ConnectorScriptProperties("scripts", "python3", 60));

        ExcelParseResult result = parser.parse(csv, 1, 1);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rowCount()).as("전체 건수는 제한과 무관하다").isEqualTo(3);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-connector:test --tests '*ExcelParserTest'`
Expected: 컴파일 실패 — `ExcelParser` 가 없다

- [ ] **Step 3: 구현**

```java
package kr.suhsaechan.palim.connector.excel;

import java.util.List;
import java.util.Map;

/** py 스크립트 출력. */
public record ExcelParseResult(List<String> fields, List<Map<String, Object>> rows, int rowCount) {
}
```

```java
package kr.suhsaechan.palim.connector.excel;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * py 스크립트 실행 설정.
 *
 * @param directory      스크립트 디렉터리
 * @param pythonExecutable 실행기(운영 이미지에서는 python3)
 * @param timeoutSeconds 초과 시 destroyForcibly. 좀비 프로세스가 쌓이면 서버가 죽는다
 */
@ConfigurationProperties(prefix = "palim.connector.script")
public record ConnectorScriptProperties(String directory, String pythonExecutable,
                                        int timeoutSeconds) {
}
```

```java
package kr.suhsaechan.palim.connector.excel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code scripts/parse_stock_excel.py} 호출.
 *
 * <p>py 호출 규약(04-CONVENTIONS)을 그대로 지킨다: 인자 배열 · stdout JSON only ·
 * {@code PYTHONIOENCODING=utf-8} · 타임아웃 + {@code destroyForcibly}.
 *
 * <p>파일 경로는 우리가 만든 임시 파일이지만 인자 배열로 넘긴다. 쉘 문자열을 조립하면
 * 파일명에 공백·따옴표가 있을 때 깨지고, 그 경로가 사용자 입력에서 오는 순간 인젝션이 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExcelParser {

    private static final String SCRIPT = "parse_stock_excel.py";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConnectorScriptProperties properties;

    /**
     * @param headerRow 헤더 행 번호(1부터)
     * @param limit     0이면 전체, 양수면 미리보기 건수
     */
    public ExcelParseResult parse(Path file, int headerRow, int limit) {
        Path script = Path.of(properties.directory(), SCRIPT);

        List<String> command = new ArrayList<>(List.of(
                properties.pythonExecutable(), script.toString(), file.toString(),
                "--header-row", String.valueOf(headerRow)));
        if (limit > 0) {
            command.add("--limit");
            command.add(String.valueOf(limit));
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PYTHONIOENCODING", "utf-8");

        Process process = null;
        try {
            process = builder.start();
            String stdout = readAll(process);

            if (!process.waitFor(properties.timeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BusinessException(ErrorCode.HOOK_TIMEOUT);
            }
            if (process.exitValue() != 0) {
                throw new BusinessException(ErrorCode.CONNECTOR_SOURCE_UNREACHABLE,
                        file.getFileName().toString());
            }
            return toResult(stdout);

        } catch (IOException e) {
            log.warn("엑셀 파싱 실행 실패 — {}", file, e);
            throw new BusinessException(ErrorCode.CONNECTOR_SOURCE_UNREACHABLE,
                    file.getFileName().toString());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.CONNECTOR_SOURCE_UNREACHABLE,
                    file.getFileName().toString());

        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String readAll(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        }
    }

    private ExcelParseResult toResult(String stdout) {
        JsonNode node = MAPPER.readTree(stdout);

        List<String> fields = new ArrayList<>();
        node.get("fields").forEach(field -> fields.add(field.asString()));

        List<Map<String, Object>> rows = MAPPER.convertValue(node.get("rows"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));

        return new ExcelParseResult(fields, rows, node.get("row_count").asInt());
    }
}
```

> `JacksonException` 은 Spring Boot 4 에서 unchecked 다. try-catch 로 감싸지 않아도 컴파일된다.
> 파싱이 깨지면 그대로 던져 실행이 FAILED 로 기록되게 둔다 — 조용히 빈 결과를 반환하면
> "0건 성공"으로 보여 아무도 이상을 눈치채지 못한다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-connector:test --tests '*ExcelParserTest'`
Expected: PASS

- [ ] **Step 5: `SourceReader` 인터페이스와 업로드 구현체**

```java
package kr.suhsaechan.palim.connector.source;

import java.util.List;
import java.util.Map;

/**
 * 원천의 필드 구조와 샘플.
 *
 * @param totalCount 샘플이 아니라 전체 건수. 화면이 "N건 중 5건 미리보기"를 표시한다
 */
public record SourceSchema(List<String> fields, List<Map<String, Object>> sampleRows,
                           int totalCount) {
}
```

```java
package kr.suhsaechan.palim.connector.source;

import java.util.Map;

/** 원천의 한 행. {@code rowNumber} 는 1부터이며 실패 행을 사람이 찾는 좌표다. */
public record SourceRow(int rowNumber, Map<String, Object> values) {
}
```

```java
package kr.suhsaechan.palim.connector.source;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 읽기 컨텍스트.
 *
 * @param file      UPLOAD 일 때의 임시 파일. 다른 유형이면 null
 * @param headerRow 헤더 행 번호(1부터)
 * @param cursor    INCREMENTAL 일 때 이 값 이후만 가져온다. FULL 이면 null
 */
public record SourceContext(UUID connectorId, Path file, int headerRow, String cursor) {
}
```

```java
package kr.suhsaechan.palim.connector.source;

import java.util.stream.Stream;
import kr.suhsaechan.palim.connector.define.SourceType;

/**
 * 원천 어댑터.
 *
 * <p>구현체를 추가해도 파이프라인 뒷단(변환·적재·실행 이력)은 바뀌지 않는다. DB·FTP·이메일·
 * 웹훅이 붙어도 이 인터페이스만 구현하면 된다.
 */
public interface SourceReader {

    SourceType type();

    SourceSchema readSchema(SourceContext context);

    Stream<SourceRow> read(SourceContext context);
}
```

```java
package kr.suhsaechan.palim.connector.source;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.excel.ExcelParseResult;
import kr.suhsaechan.palim.connector.excel.ExcelParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 업로드 파일(엑셀·CSV) 원천. */
@Component
@RequiredArgsConstructor
public class UploadSourceReader implements SourceReader {

    /** 미리보기 행 수. 사람이 눈으로 확인하기에 5행이면 컬럼 성격이 드러난다. */
    private static final int SAMPLE_LIMIT = 5;

    private final ExcelParser excelParser;

    @Override
    public SourceType type() {
        return SourceType.UPLOAD;
    }

    @Override
    public SourceSchema readSchema(SourceContext context) {
        ExcelParseResult result = excelParser.parse(context.file(), context.headerRow(),
                SAMPLE_LIMIT);
        return new SourceSchema(result.fields(), result.rows(), result.rowCount());
    }

    @Override
    public Stream<SourceRow> read(SourceContext context) {
        ExcelParseResult result = excelParser.parse(context.file(), context.headerRow(), 0);
        List<Map<String, Object>> rows = result.rows();

        // 행 번호는 1부터. 실패 행을 사람이 원본 파일에서 찾을 수 있어야 한다.
        return IntStream.range(0, rows.size())
                .mapToObj(index -> new SourceRow(index + 1, rows.get(index)));
    }
}
```

- [ ] **Step 6: 설정 추가**

`palim-app/src/main/resources/application.yaml` 의 `palim:` 아래에 추가:

```yaml
  connector:
    script:
      directory: ${PALIM_SCRIPT_DIR:scripts}
      python-executable: ${PALIM_PYTHON:python3}
      timeout-seconds: 120
```

- [ ] **Step 7: 커밋**

```bash
git add palim-connector/src palim-app/src/main/resources/application.yaml
git commit -m "범용 데이터 연동 프레임워크와 물품 표준 모델 : feat : 엑셀 파서 어댑터와 원천 인터페이스 추가 https://github.com/Cassiiopeia/palim/issues/53"
```

---

## Task 7: 스키마 드리프트 감지

**Files:**
- Create: `.../schema/SchemaSnapshot.java` · `DriftVerdict.java` · `DriftDetector.java`
- Test: `palim-connector/src/test/java/.../schema/DriftDetectorTest.java`

**Interfaces:**
- Consumes: Task 6 의 `SourceSchema`
- Produces:
  - `record SchemaSnapshot(List<String> fields)`
  - `record DriftVerdict(boolean blocking, List<String> removed, List<String> added, String summary)`
  - `DriftDetector.detect(SchemaSnapshot confirmed, SourceSchema current, Set<String> mappedFields)`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.suhsaechan.palim.connector.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import kr.suhsaechan.palim.connector.source.SourceSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 드리프트 감지.
 *
 * <p>이 시스템의 최악 실패는 원천 양식이 바뀌었는데 조용히 잘못된 데이터가 들어가는 것이다.
 * 다만 과민하면 사람이 감지를 꺼버리므로, <b>매핑에 실제로 쓰는 필드</b>가 사라졌을 때만 막는다.
 */
class DriftDetectorTest {

    private final DriftDetector detector = new DriftDetector();

    @Test
    @DisplayName("매핑에 쓰는 필드가 사라지면 막는다")
    void 사용중인_필드가_사라지면_차단() {
        SchemaSnapshot confirmed = new SchemaSnapshot(List.of("코드", "이름", "수량"));
        SourceSchema current = new SourceSchema(List.of("코드", "이름"), List.of(), 0);

        DriftVerdict verdict = detector.detect(confirmed, current, Set.of("코드", "수량"));

        assertThat(verdict.blocking()).isTrue();
        assertThat(verdict.removed()).containsExactly("수량");
    }

    @Test
    @DisplayName("매핑에 쓰지 않는 필드가 사라지면 통과시킨다")
    void 미사용_필드가_사라지면_통과() {
        SchemaSnapshot confirmed = new SchemaSnapshot(List.of("코드", "이름", "메모"));
        SourceSchema current = new SourceSchema(List.of("코드", "이름"), List.of(), 0);

        DriftVerdict verdict = detector.detect(confirmed, current, Set.of("코드", "이름"));

        assertThat(verdict.blocking()).as("쓰지 않는 필드는 없어져도 상관없다").isFalse();
        assertThat(verdict.removed()).containsExactly("메모");
    }

    @Test
    @DisplayName("새 필드가 추가되면 통과시킨다")
    void 필드_추가는_통과() {
        SchemaSnapshot confirmed = new SchemaSnapshot(List.of("코드", "이름"));
        SourceSchema current = new SourceSchema(List.of("코드", "이름", "신규"), List.of(), 0);

        DriftVerdict verdict = detector.detect(confirmed, current, Set.of("코드", "이름"));

        assertThat(verdict.blocking())
                .as("추가만으로 업무를 멈추면 사람이 감지를 꺼버린다").isFalse();
        assertThat(verdict.added()).containsExactly("신규");
    }

    @Test
    @DisplayName("변화가 없으면 통과다")
    void 변화가_없으면_통과() {
        SchemaSnapshot confirmed = new SchemaSnapshot(List.of("코드", "이름"));
        SourceSchema current = new SourceSchema(List.of("코드", "이름"), List.of(), 0);

        DriftVerdict verdict = detector.detect(confirmed, current, Set.of("코드"));

        assertThat(verdict.blocking()).isFalse();
        assertThat(verdict.removed()).isEmpty();
        assertThat(verdict.added()).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-connector:test --tests '*DriftDetectorTest'`
Expected: 컴파일 실패 — `DriftDetector` 없음

- [ ] **Step 3: 구현**

```java
package kr.suhsaechan.palim.connector.schema;

import java.util.List;

/** 매핑 확정 시점의 원천 필드 목록. {@code connector_mapping.source_schema} 에 저장된다. */
public record SchemaSnapshot(List<String> fields) {
}
```

```java
package kr.suhsaechan.palim.connector.schema;

import java.util.List;

/**
 * 드리프트 판정.
 *
 * @param blocking true 면 적재하지 않고 중단한다
 * @param removed  확정 당시에는 있었으나 지금 없는 필드
 * @param added    확정 당시에는 없었으나 지금 있는 필드
 */
public record DriftVerdict(boolean blocking, List<String> removed, List<String> added,
                           String summary) {
}
```

```java
package kr.suhsaechan.palim.connector.schema;

import java.util.List;
import java.util.Set;
import kr.suhsaechan.palim.connector.source.SourceSchema;
import org.springframework.stereotype.Component;

/**
 * 원천 양식 변화 감지.
 *
 * <p>변화 종류에 따라 대응을 나눈다. "다르면 무조건 중단"으로 만들면 컬럼이 하나 추가되기만
 * 해도 업무가 멈추고, 그러면 <b>사람이 감지를 꺼버린다.</b> 꺼진 안전장치는 없는 것과 같다.
 *
 * <table border="1">
 *   <caption>대응</caption>
 *   <tr><td>매핑에 쓰던 필드가 사라짐</td><td>중단</td></tr>
 *   <tr><td>매핑에 쓰지 않는 필드가 사라짐</td><td>경고</td></tr>
 *   <tr><td>새 필드가 추가됨</td><td>경고 — attributes 로 들어간다</td></tr>
 * </table>
 */
@Component
public class DriftDetector {

    public DriftVerdict detect(SchemaSnapshot confirmed, SourceSchema current,
                               Set<String> mappedFields) {
        Set<String> currentFields = Set.copyOf(current.fields());
        Set<String> confirmedFields = Set.copyOf(confirmed.fields());

        List<String> removed = confirmed.fields().stream()
                .filter(field -> !currentFields.contains(field))
                .toList();

        List<String> added = current.fields().stream()
                .filter(field -> !confirmedFields.contains(field))
                .toList();

        List<String> blockingFields = removed.stream()
                .filter(mappedFields::contains)
                .toList();

        boolean blocking = !blockingFields.isEmpty();
        String summary = blocking
                ? "매핑에 사용 중인 항목이 사라졌습니다: " + String.join(", ", blockingFields)
                : describeNonBlocking(removed, added);

        return new DriftVerdict(blocking, removed, added, summary);
    }

    private String describeNonBlocking(List<String> removed, List<String> added) {
        if (removed.isEmpty() && added.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (!added.isEmpty()) {
            builder.append("새 항목: ").append(String.join(", ", added));
        }
        if (!removed.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append(" / ");
            }
            builder.append("사라진 항목(미사용): ").append(String.join(", ", removed));
        }
        return builder.toString();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-connector:test --tests '*DriftDetectorTest'`
Expected: PASS (4건)

- [ ] **Step 5: 커밋**

```bash
git add palim-connector/src
git commit -m "범용 데이터 연동 프레임워크와 물품 표준 모델 : feat : 스키마 드리프트 감지 추가 https://github.com/Cassiiopeia/palim/issues/53"
```

---

## Task 8: 단위 환산

**Files:**
- Create: `.../unit/UnitConversion.java` · `UnitConversionRepository.java` · `UnitConverter.java` · `ConvertedQuantity.java`
- Test: `palim-connector/src/test/java/.../unit/UnitConverterTest.java`

**Interfaces:**
- Consumes: Task 2 의 `unit_conversion` 테이블
- Produces:
  - `record ConvertedQuantity(BigDecimal quantity, String unit, BigDecimal baseQuantity, String baseUnit)`
  - `UnitConverter.convert(UUID tenantId, String itemRef, BigDecimal quantity, String unit, String defaultUnit)`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.suhsaechan.palim.connector.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 단위 환산.
 *
 * <p>실패 조건을 좁게 잡는 것이 핵심이다. "규칙이 없으면 무조건 실패"로 만들면 단위 개념이
 * 없는 원천이 통째로 막힌다 — 실측한 두 원천 모두 단위 컬럼이 없다.
 */
class UnitConverterTest {

    private static final UUID TENANT = UUID.randomUUID();

    private UnitConversionRepository repository;
    private UnitConverter converter;

    @BeforeEach
    void setUp() {
        repository = mock(UnitConversionRepository.class);
        converter = new UnitConverter(repository);
    }

    @Test
    @DisplayName("단위가 비어 있으면 환산 없이 기본 단위로 통과한다")
    void 단위가_없으면_통과() {
        ConvertedQuantity result = converter.convert(TENANT, "A-001",
                new BigDecimal("120"), null, "EA");

        assertThat(result.baseQuantity()).isEqualByComparingTo("120");
        assertThat(result.baseUnit()).isEqualTo("EA");
    }

    @Test
    @DisplayName("품목별 규칙이 있으면 그것으로 환산한다")
    void 품목별_규칙으로_환산() {
        when(repository.findRule(TENANT, "A-001", "BOX", "EA"))
                .thenReturn(Optional.of(new BigDecimal("12")));

        ConvertedQuantity result = converter.convert(TENANT, "A-001",
                new BigDecimal("12"), "BOX", "EA");

        assertThat(result.baseQuantity()).isEqualByComparingTo("144");
        assertThat(result.quantity()).as("원본은 그대로 보존한다").isEqualByComparingTo("12");
        assertThat(result.unit()).isEqualTo("BOX");
    }

    @Test
    @DisplayName("단위가 명시됐는데 규칙이 없으면 실패시킨다")
    void 규칙이_없으면_실패() {
        when(repository.findRule(TENANT, "A-001", "BOX", "EA")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> converter.convert(TENANT, "A-001",
                new BigDecimal("12"), "BOX", "EA"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNIT_CONVERSION_NOT_FOUND);
    }

    @Test
    @DisplayName("단위가 기본 단위와 같으면 규칙 없이 통과한다")
    void 같은_단위는_규칙이_필요없다() {
        ConvertedQuantity result = converter.convert(TENANT, "A-001",
                new BigDecimal("5"), "EA", "EA");

        assertThat(result.baseQuantity()).isEqualByComparingTo("5");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-connector:test --tests '*UnitConverterTest'`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

```java
package kr.suhsaechan.palim.connector.unit;

import java.math.BigDecimal;

/**
 * 환산 결과.
 *
 * <p>원본과 환산값을 <b>둘 다</b> 남긴다. 집계·대사는 {@code baseQuantity} 만 쓰지만,
 * 원본이 없으면 "원천이 뭐라고 줬는지"를 나중에 확인할 수 없다.
 */
public record ConvertedQuantity(BigDecimal quantity, String unit,
                                BigDecimal baseQuantity, String baseUnit) {
}
```

```java
package kr.suhsaechan.palim.connector.unit;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UnitConversionRepository extends JpaRepository<UnitConversion, UUID> {

    /**
     * 품목별 규칙을 먼저, 없으면 전역 규칙을 찾는다.
     *
     * <p>{@code item_ref IS NULL} 이 전역 규칙이다. 정렬로 품목별을 앞에 두어 하나의 쿼리로
     * 우선순위를 표현한다.
     */
    @Query("""
            select c.factor from UnitConversion c
            where c.tenantId = :tenantId
              and (c.itemRef = :itemRef or c.itemRef is null)
              and c.fromUnit = :fromUnit and c.toUnit = :toUnit
            order by case when c.itemRef is null then 1 else 0 end
            limit 1
            """)
    Optional<BigDecimal> findRule(@Param("tenantId") UUID tenantId,
                                  @Param("itemRef") String itemRef,
                                  @Param("fromUnit") String fromUnit,
                                  @Param("toUnit") String toUnit);
}
```

```java
package kr.suhsaechan.palim.connector.unit;

import java.math.BigDecimal;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 단위 환산.
 *
 * <p>실패 조건을 좁게 잡는다.
 *
 * <table border="1">
 *   <caption>분기</caption>
 *   <tr><td>단위가 비어 있음</td><td>기본 단위로 통과 — 단위 개념이 없는 원천이 많다</td></tr>
 *   <tr><td>단위 = 기본 단위</td><td>규칙 없이 통과</td></tr>
 *   <tr><td>단위가 다르고 규칙 있음</td><td>환산</td></tr>
 *   <tr><td>단위가 다르고 규칙 없음</td><td><b>실패</b></td></tr>
 * </table>
 *
 * <p>마지막 줄이 이 클래스의 존재 이유다. 조용히 1:1 로 넘기면 BOX 12개가 EA 12개로 둔갑하고,
 * 그 오류는 대사 결과가 이상해질 때까지 아무도 모른다.
 */
@Component
@RequiredArgsConstructor
public class UnitConverter {

    private final UnitConversionRepository repository;

    public ConvertedQuantity convert(UUID tenantId, String itemRef, BigDecimal quantity,
                                     String unit, String defaultUnit) {
        if (!StringUtils.hasText(unit) || unit.equals(defaultUnit)) {
            return new ConvertedQuantity(quantity, unit, quantity, defaultUnit);
        }

        BigDecimal factor = repository.findRule(tenantId, itemRef, unit, defaultUnit)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNIT_CONVERSION_NOT_FOUND, unit, defaultUnit));

        return new ConvertedQuantity(quantity, unit, quantity.multiply(factor), defaultUnit);
    }
}
```

`UnitConversion` 엔티티는 Task 2 의 컬럼(`tenantId` · `itemRef` · `fromUnit` · `toUnit` ·
`factor`)에 1:1 대응하며 팩토리는 `of(UUID tenantId, String itemRef, String fromUnit,
String toUnit, BigDecimal factor)` 다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-connector:test --tests '*UnitConverterTest'`
Expected: PASS (4건)

- [ ] **Step 5: 커밋**

```bash
git add palim-connector/src
git commit -m "범용 데이터 연동 프레임워크와 물품 표준 모델 : feat : 단위 환산 추가 https://github.com/Cassiiopeia/palim/issues/53"
```

---

## Task 9: 변환 규칙 엔진과 자연키

**Files:**
- Create: `.../transform/TransformType.java` · `TransformRule.java` · `TransformEngine.java` · `MappedRow.java`
- Create: `.../key/NaturalKeyBuilder.java`
- Test: `.../transform/TransformEngineTest.java` · `.../key/NaturalKeyBuilderTest.java`

**Interfaces:**
- Consumes: Task 2 의 `ConnectorFieldMap` · `TargetField`, Task 6 의 `SourceRow`, Task 8 의 `UnitConverter`
- Produces:
  - `record MappedRow(int rowNumber, Map<String,Object> values, Map<String,Object> attributes)`
  - `TransformEngine.map(SourceRow row, List<ConnectorFieldMap> maps, List<TargetField> fields)` → `MappedRow`
  - `NaturalKeyBuilder.build(Map<String,Object> values, List<String> keyFields)` → `String`

- [ ] **Step 1: 자연키 테스트 먼저 작성**

```java
package kr.suhsaechan.palim.connector.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NaturalKeyBuilderTest {

    private final NaturalKeyBuilder builder = new NaturalKeyBuilder();

    @Test
    @DisplayName("키 필드 값을 순서대로 이어 붙인다")
    void 키를_조합한다() {
        Map<String, Object> values = Map.of(
                "source", "ERP", "item_ref", "A-001", "warehouse_code", "W1");

        String key = builder.build(values, List.of("source", "item_ref", "warehouse_code"));

        assertThat(key).isEqualTo("ERPA-001W1");
    }

    @Test
    @DisplayName("값이 없는 키 필드는 빈 문자열로 채워 자리를 유지한다")
    void 빈_값도_자리를_지킨다() {
        Map<String, Object> values = new HashMap<>();
        values.put("source", "ERP");
        values.put("lot_code", null);

        String key = builder.build(values, List.of("source", "lot_code"));

        assertThat(key).as("자리가 밀리면 다른 조합과 충돌한다").isEqualTo("ERP");
    }

    @Test
    @DisplayName("키 필드가 전부 비면 실패시킨다")
    void 전부_비면_실패() {
        Map<String, Object> values = new HashMap<>();
        values.put("source", null);
        values.put("item_ref", "");

        assertThatThrownBy(() -> builder.build(values, List.of("source", "item_ref")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NATURAL_KEY_INCOMPLETE);
    }
}
```

- [ ] **Step 2: 자연키 구현**

```java
package kr.suhsaechan.palim.connector.key;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 자연키 생성.
 *
 * <p>구분자로 유니트 구분자(U+001F)를 쓴다. 사람이 입력하는 값에 등장할 일이 없어
 * {@code "A|B"} 와 {@code "A", "B"} 가 같은 키가 되는 충돌을 피한다.
 *
 * <p>값이 없는 필드도 <b>자리를 유지</b>한다. 건너뛰면 뒤 값이 앞으로 밀려 다른 조합과
 * 같은 키가 된다.
 */
@Component
public class NaturalKeyBuilder {

    private static final String SEPARATOR = "";

    public String build(Map<String, Object> values, List<String> keyFields) {
        List<String> parts = keyFields.stream()
                .map(field -> Objects.toString(values.get(field), ""))
                .toList();

        if (parts.stream().noneMatch(StringUtils::hasText)) {
            throw new BusinessException(ErrorCode.NATURAL_KEY_INCOMPLETE,
                    String.join(", ", keyFields));
        }
        return String.join(SEPARATOR, parts);
    }
}
```

- [ ] **Step 3: 변환 규칙 테스트 작성**

```java
package kr.suhsaechan.palim.connector.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.model.FieldDataType;
import kr.suhsaechan.palim.connector.source.SourceRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransformEngineTest {

    private final TransformEngine engine = new TransformEngine();

    @Test
    @DisplayName("매핑된 필드를 목표 키로 옮긴다")
    void 필드를_옮긴다() {
        SourceRow row = new SourceRow(1, Map.of("품목코드", "A-001", "재고수량", "120"));

        MappedRow mapped = engine.map(row,
                List.of(fieldMap("품목코드", "item_ref"), fieldMap("재고수량", "quantity")),
                List.of(field("item_ref", FieldDataType.STRING, true),
                        field("quantity", FieldDataType.DECIMAL, true)));

        assertThat(mapped.values().get("item_ref")).isEqualTo("A-001");
        assertThat(mapped.values().get("quantity")).hasToString("120");
    }

    @Test
    @DisplayName("매핑되지 않은 원천 컬럼은 attributes 로 보존한다")
    void 미매핑_컬럼을_보존한다() {
        SourceRow row = new SourceRow(1, Map.of("품목코드", "A-001", "비고", "메모"));

        MappedRow mapped = engine.map(row, List.of(fieldMap("품목코드", "item_ref")),
                List.of(field("item_ref", FieldDataType.STRING, true)));

        assertThat(mapped.attributes())
                .as("버리면 과거 시점 데이터를 다시 받을 수 없다")
                .containsEntry("비고", "메모");
    }

    @Test
    @DisplayName("필수 필드가 비면 실패시킨다")
    void 필수_필드가_비면_실패() {
        SourceRow row = new SourceRow(1, Map.of("품목코드", ""));

        assertThatThrownBy(() -> engine.map(row, List.of(fieldMap("품목코드", "item_ref")),
                List.of(field("item_ref", FieldDataType.STRING, true))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REQUIRED_FIELD_MISSING);
    }

    @Test
    @DisplayName("숫자로 읽을 수 없는 값은 실패시킨다")
    void 타입이_안_맞으면_실패() {
        SourceRow row = new SourceRow(1, Map.of("재고수량", "없음"));

        assertThatThrownBy(() -> engine.map(row, List.of(fieldMap("재고수량", "quantity")),
                List.of(field("quantity", FieldDataType.DECIMAL, true))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FIELD_TYPE_MISMATCH);
    }

    // 테스트 헬퍼 — 실제 엔티티 대신 값 객체로 최소 구성만 만든다
    private FieldMapping fieldMap(String source, String target) {
        return new FieldMapping(source, target, TransformRule.none());
    }

    private TargetFieldSpec field(String key, FieldDataType type, boolean required) {
        return new TargetFieldSpec(key, type, required, null);
    }
}
```

> `FieldMapping` · `TargetFieldSpec` 은 엔티티에 의존하지 않는 값 객체다. 변환 엔진이 JPA 를
> 모르게 해야 단위 테스트가 컨테이너 없이 돈다.

- [ ] **Step 4: 변환 엔진 구현**

```java
package kr.suhsaechan.palim.connector.transform;

/** 값 변환 종류. 화면에서 드롭다운으로 고른다. */
public enum TransformType {
    NONE, TRIM, UPPER, LOWER, DATE_FORMAT, NUMBER_STRIP, CODE_REPLACE, DEFAULT_IF_EMPTY
}
```

```java
package kr.suhsaechan.palim.connector.transform;

import java.util.Map;

/**
 * 선언적 변환 규칙.
 *
 * @param type   변환 종류
 * @param params 종류별 파라미터(패턴·치환표 등)
 */
public record TransformRule(TransformType type, Map<String, String> params) {

    public static TransformRule none() {
        return new TransformRule(TransformType.NONE, Map.of());
    }
}
```

```java
package kr.suhsaechan.palim.connector.transform;

/** 원천 필드 → 목표 필드 연결. 엔티티가 아니라 값 객체다(단위 테스트가 컨테이너 없이 돈다). */
public record FieldMapping(String sourceField, String targetFieldKey, TransformRule rule) {
}
```

```java
package kr.suhsaechan.palim.connector.transform;

import kr.suhsaechan.palim.connector.model.FieldDataType;

public record TargetFieldSpec(String fieldKey, FieldDataType dataType, boolean required,
                              String defaultValue) {
}
```

```java
package kr.suhsaechan.palim.connector.transform;

import java.util.List;
import java.util.Map;

/**
 * 변환 결과.
 *
 * @param values     목표 필드 키 → 값
 * @param attributes 매핑되지 않은 원천 컬럼. 버리지 않는다
 */
public record MappedRow(int rowNumber, Map<String, Object> values,
                        Map<String, Object> attributes) {
}
```

```java
package kr.suhsaechan.palim.connector.transform;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.model.FieldDataType;
import kr.suhsaechan.palim.connector.source.SourceRow;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 매핑 정의대로 원천 행을 목표 형태로 옮긴다.
 *
 * <p>JPA 엔티티를 모른다. 값 객체만 받아 값 객체를 돌려주므로 단위 테스트가 컨테이너 없이
 * 돌고, 규칙이 늘어도 이 클래스만 본다.
 */
@Component
public class TransformEngine {

    public MappedRow map(SourceRow row, List<FieldMapping> mappings,
                         List<TargetFieldSpec> fields) {
        Map<String, TargetFieldSpec> specByKey = new HashMap<>();
        fields.forEach(field -> specByKey.put(field.fieldKey(), field));

        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> consumed = new java.util.HashSet<>();

        for (FieldMapping mapping : mappings) {
            Object raw = row.values().get(mapping.sourceField());
            consumed.add(mapping.sourceField());

            String applied = applyRule(Objects.toString(raw, ""), mapping.rule());
            TargetFieldSpec spec = specByKey.get(mapping.targetFieldKey());

            if (spec == null) {
                continue; // 목표 모델에 없는 키로 매핑돼 있으면 무시한다
            }
            values.put(mapping.targetFieldKey(), coerce(applied, spec));
        }

        // 필수 검사는 매핑을 다 돌린 뒤에 한다. 매핑 순서에 결과가 좌우되면 안 된다.
        for (TargetFieldSpec spec : fields) {
            if (spec.required() && !StringUtils.hasText(Objects.toString(
                    values.get(spec.fieldKey()), ""))) {
                throw new BusinessException(ErrorCode.REQUIRED_FIELD_MISSING, spec.fieldKey());
            }
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        row.values().forEach((key, value) -> {
            if (!consumed.contains(key)) {
                attributes.put(key, value);
            }
        });

        return new MappedRow(row.rowNumber(), values, attributes);
    }

    private String applyRule(String value, TransformRule rule) {
        return switch (rule.type()) {
            case NONE -> value;
            case TRIM -> value.trim();
            case UPPER -> value.toUpperCase();
            case LOWER -> value.toLowerCase();
            // 숫자만 남긴다. "1,200 개" 같은 표기가 흔하다.
            case NUMBER_STRIP -> value.replaceAll("[^0-9.\\-]", "");
            case CODE_REPLACE -> rule.params().getOrDefault(value, value);
            case DEFAULT_IF_EMPTY ->
                    StringUtils.hasText(value) ? value : rule.params().get("value");
            case DATE_FORMAT -> value; // 파싱은 coerce 에서 패턴과 함께 처리한다
        };
    }

    private Object coerce(String value, TargetFieldSpec spec) {
        if (!StringUtils.hasText(value)) {
            return spec.defaultValue();
        }
        try {
            return switch (spec.dataType()) {
                case STRING -> value;
                case INTEGER -> Long.valueOf(value.trim());
                case DECIMAL -> new BigDecimal(value.trim());
                case BOOLEAN -> Boolean.valueOf(value.trim());
                case DATE -> LocalDate.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
                case TIMESTAMP -> java.time.Instant.parse(value.trim());
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new BusinessException(ErrorCode.FIELD_TYPE_MISMATCH,
                    spec.fieldKey(), value, spec.dataType().name());
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-connector:test --tests '*TransformEngineTest' --tests '*NaturalKeyBuilderTest'`
Expected: PASS (7건)

- [ ] **Step 6: 커밋**

```bash
git add palim-connector/src
git commit -m "범용 데이터 연동 프레임워크와 물품 표준 모델 : feat : 변환 규칙 엔진과 자연키 생성 추가 https://github.com/Cassiiopeia/palim/issues/53"
```

---

## Task 10: 적재기 — 스테이징과 표준 테이블

**Files:**
- Create: `.../write/RecordWriter.java` · `StagingWriter.java` · `StandardModelWriter.java` · `CustomRecordWriter.java` · `WriteResult.java`
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/ConnectorWriterIntegrationTest.java`

**Interfaces:**
- Consumes: Task 9 의 `MappedRow` · `NaturalKeyBuilder`, Task 4 의 `ConnectorStaging`
- Produces:
  - `RecordWriter.write(UUID tenantId, UUID runId, TargetModel model, List<MappedRow> chunk)` → `WriteResult`
  - `record WriteResult(int inserted, int updated)`
  - `RecordWriter.rollback(UUID tenantId, UUID runId)`

- [ ] **Step 1: 통합 테스트 작성 (실패)**

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.transform.MappedRow;
import kr.suhsaechan.palim.connector.write.StagingWriter;
import kr.suhsaechan.palim.connector.write.StandardModelWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 적재기.
 *
 * <p>가장 중요한 검증은 <b>TEST 실행이 운영 테이블을 건드리지 않는다</b>는 것과
 * <b>같은 데이터를 두 번 넣어도 중복이 생기지 않는다</b>는 것이다. 이 둘이 깨지면 사람이
 * 시스템을 믿지 못하고, 믿지 못하면 쓰지 않는다.
 */
class ConnectorWriterIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private StagingWriter stagingWriter;
    @Autowired private StandardModelWriter standardWriter;
    @Autowired private TargetModelRepository targetModelRepository;
    @Autowired private JdbcClient jdbcClient;

    @Test
    @DisplayName("TEST 적재는 표준 테이블을 건드리지 않는다")
    void 테스트_적재는_운영을_건드리지_않는다() {
        TargetModel model = snapshotModel();
        UUID runId = UUID.randomUUID();

        stagingWriter.write(TENANT, runId, model, List.of(snapshotRow("A-001")));

        assertThat(countStaging(runId)).isEqualTo(1);
        assertThat(countSnapshot("A-001")).as("운영 테이블은 그대로여야 한다").isZero();
    }

    @Test
    @DisplayName("스테이징 비우기는 그 실행분만 지운다")
    void 스테이징을_비운다() {
        TargetModel model = snapshotModel();
        UUID keep = UUID.randomUUID();
        UUID drop = UUID.randomUUID();
        stagingWriter.write(TENANT, keep, model, List.of(snapshotRow("A-001")));
        stagingWriter.write(TENANT, drop, model, List.of(snapshotRow("A-002")));

        stagingWriter.rollback(TENANT, drop);

        assertThat(countStaging(drop)).isZero();
        assertThat(countStaging(keep)).as("다른 실행분은 남아야 한다").isEqualTo(1);
    }

    @Test
    @DisplayName("같은 자연키를 두 번 적재해도 행이 늘지 않는다")
    void 재적재가_중복을_만들지_않는다() {
        TargetModel model = snapshotModel();

        standardWriter.write(TENANT, UUID.randomUUID(), model, List.of(snapshotRow("A-100")));
        standardWriter.write(TENANT, UUID.randomUUID(), model, List.of(snapshotRow("A-100")));

        assertThat(countSnapshot("A-100")).isEqualTo(1);
    }

    private TargetModel snapshotModel() {
        return targetModelRepository.findByTenantIdAndCode(TENANT, "std_stock_snapshot")
                .orElseThrow();
    }

    private MappedRow snapshotRow(String itemRef) {
        return new MappedRow(1, Map.of(
                "item_ref", itemRef,
                "source", "TEST_SOURCE",
                "base_at", Instant.parse("2026-08-12T00:00:00Z"),
                "quantity", new BigDecimal("10"),
                "base_quantity", new BigDecimal("10"),
                "base_unit", "EA"), Map.of());
    }

    private int countStaging(UUID runId) {
        return jdbcClient.sql("SELECT count(*)::int FROM connector_staging WHERE run_id = :id")
                .param("id", runId).query(Integer.class).single();
    }

    private int countSnapshot(String itemRef) {
        return jdbcClient.sql(
                        "SELECT count(*)::int FROM std_stock_snapshot WHERE item_ref = :ref")
                .param("ref", itemRef).query(Integer.class).single();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-app:test --tests '*ConnectorWriterIntegrationTest'`
Expected: 컴파일 실패 — `StagingWriter` 없음

- [ ] **Step 3: 인터페이스와 스테이징 구현**

```java
package kr.suhsaechan.palim.connector.write;

/** 적재 결과. */
public record WriteResult(int inserted, int updated) {

    public static WriteResult of(int inserted, int updated) {
        return new WriteResult(inserted, updated);
    }
}
```

```java
package kr.suhsaechan.palim.connector.write;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.transform.MappedRow;

/** 적재기. TEST 와 LIVE 가 서로 다른 구현을 쓴다. */
public interface RecordWriter {

    WriteResult write(UUID tenantId, UUID runId, TargetModel model, List<MappedRow> chunk);

    /** 그 실행분만 되돌린다. */
    void rollback(UUID tenantId, UUID runId);
}
```

```java
package kr.suhsaechan.palim.connector.write;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.connector.key.NaturalKeyBuilder;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.run.ConnectorStaging;
import kr.suhsaechan.palim.connector.run.ConnectorStagingRepository;
import kr.suhsaechan.palim.connector.transform.MappedRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * TEST 실행 적재기.
 *
 * <p>운영 테이블에 <b>닿지 않는다.</b> 원래 설계는 표준 테이블에 넣고 지우는 방식이었는데,
 * 지우기 전에 도메인 로직이 읽으면 오염된 결과가 나오고, UPSERT 와 실행 단위 롤백이
 * 양립하지도 않았다(같은 행을 덮어쓰면 소유 실행이 바뀐다).
 */
@Component
@RequiredArgsConstructor
public class StagingWriter implements RecordWriter {

    private final ConnectorStagingRepository repository;
    private final NaturalKeyBuilder keyBuilder;

    @Override
    @Transactional
    public WriteResult write(UUID tenantId, UUID runId, TargetModel model,
                             List<MappedRow> chunk) {
        List<ConnectorStaging> entities = chunk.stream()
                .map(row -> ConnectorStaging.of(tenantId, runId, row.rowNumber(),
                        keyBuilder.build(row.values(), model.getNaturalKeyFields()),
                        row.values()))
                .toList();

        repository.saveAll(entities);
        return WriteResult.of(entities.size(), 0);
    }

    @Override
    @Transactional
    public void rollback(UUID tenantId, UUID runId) {
        repository.deleteByTenantIdAndRunId(tenantId, runId);
    }
}
```

- [ ] **Step 4: 표준 테이블 적재기 구현**

```java
package kr.suhsaechan.palim.connector.write;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.connector.key.NaturalKeyBuilder;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.transform.MappedRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * LIVE 실행 적재기 — 표준 테이블에 UPSERT.
 *
 * <p>JPA 가 아니라 {@link JdbcClient} 를 쓴다. 목표 테이블이 실행 시점에 결정되므로 엔티티
 * 타입을 컴파일 시점에 고정할 수 없고, {@code ON CONFLICT} 는 JPA 로 표현되지 않는다.
 *
 * <p>UPSERT 직전 값을 {@code connector_undo_log} 에 남긴다. 되돌리기는 <b>가장 최근 실행
 * 하나</b>만 허용한다 — 그 이전까지 거슬러 오르면 이후 실행들과 뒤엉켜 어떤 상태로 돌아가는지
 * 아무도 설명할 수 없다.
 */
@Component
@RequiredArgsConstructor
public class StandardModelWriter implements RecordWriter {

    private final JdbcClient jdbcClient;
    private final NaturalKeyBuilder keyBuilder;

    @Override
    @Transactional
    public WriteResult write(UUID tenantId, UUID runId, TargetModel model,
                             List<MappedRow> chunk) {
        String table = model.getTableName();
        List<String> keyFields = model.getNaturalKeyFields();
        int inserted = 0;

        for (MappedRow row : chunk) {
            String naturalKey = keyBuilder.build(row.values(), keyFields);
            saveUndoSnapshot(tenantId, runId, table, naturalKey, keyFields, row);

            Map<String, Object> columns = new LinkedHashMap<>(row.values());
            columns.put("id", UuidV7.generate());
            columns.put("tenant_id", tenantId);
            columns.put("run_id", runId);
            columns.put("attributes", row.attributes());

            jdbcClient.sql(upsertSql(table, columns.keySet(), keyFields))
                    .params(columns)
                    .update();
            inserted++;
        }
        return WriteResult.of(inserted, 0);
    }

    /**
     * UPSERT 직전 값 보관.
     *
     * <p>대상 행이 없으면 {@code previous_row} 가 NULL 이고, 되돌리기는 그 행의 삭제가 된다.
     */
    private void saveUndoSnapshot(UUID tenantId, UUID runId, String table, String naturalKey,
                                  List<String> keyFields, MappedRow row) {
        String where = keyFields.stream()
                .map(field -> field + " IS NOT DISTINCT FROM :" + field)
                .reduce((a, b) -> a + " AND " + b)
                .orElseThrow();

        jdbcClient.sql("""
                        INSERT INTO connector_undo_log
                            (id, tenant_id, run_id, table_name, natural_key, previous_row,
                             created_at, updated_at)
                        SELECT :undoId, :tenantId, :runId, :tableName, :naturalKey,
                               to_jsonb(t), now(), now()
                        FROM %s t
                        WHERE t.tenant_id = :tenantId AND %s
                        """.formatted(table, where))
                .param("undoId", UuidV7.generate())
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("tableName", table)
                .param("naturalKey", naturalKey)
                .params(keyFields.stream().collect(
                        LinkedHashMap::new, (m, f) -> m.put(f, row.values().get(f)),
                        LinkedHashMap::putAll))
                .update();
    }

    private String upsertSql(String table, java.util.Set<String> columns, List<String> keyFields) {
        String columnList = String.join(", ", columns);
        String valueList = columns.stream().map(c -> ":" + c)
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        String updates = columns.stream()
                .filter(c -> !c.equals("id") && !keyFields.contains(c))
                .map(c -> c + " = EXCLUDED." + c)
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        String conflict = String.join(", ", keyFields);

        return """
                INSERT INTO %s (%s, created_at, updated_at)
                VALUES (%s, now(), now())
                ON CONFLICT (tenant_id, %s) DO UPDATE SET %s, updated_at = now()
                """.formatted(table, columnList, valueList, conflict, updates);
    }

    @Override
    @Transactional
    public void rollback(UUID tenantId, UUID runId) {
        // 이번 실행이 처음 만든 행(previous_row IS NULL)은 삭제, 덮어쓴 행은 이전 값으로 복원.
        // 구현은 Task 12 의 되돌리기 서비스에서 완성한다.
        throw new UnsupportedOperationException("Task 12 에서 구현");
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-app:test --tests '*ConnectorWriterIntegrationTest'`
Expected: PASS (3건)

- [ ] **Step 6: 커밋**

```bash
git add palim-connector/src palim-app/src/test/java/kr/suhsaechan/palim/integration/ConnectorWriterIntegrationTest.java
git commit -m "범용 데이터 연동 프레임워크와 물품 표준 모델 : feat : 스테이징과 표준 테이블 적재기 추가 https://github.com/Cassiiopeia/palim/issues/53"
```

---

## Task 11: 실행 오케스트레이터

**Files:**
- Create: `.../run/ConnectorRunner.java` · `RunRequest.java` · `RunLock.java`
- Create: `.../model/TargetFieldBootstrap.java` (표준 모델 필드 등록)
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/ConnectorRunnerIntegrationTest.java`

**Interfaces:**
- Consumes: Task 6~10 전부
- Produces: `ConnectorRunner.run(RunRequest)` → `ConnectorRun`

- [ ] **Step 1: 오케스트레이터 테스트 작성**

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.run.ConnectorRun;
import kr.suhsaechan.palim.connector.run.ConnectorRunner;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.run.RunRequest;
import kr.suhsaechan.palim.connector.run.RunStatus;
import kr.suhsaechan.palim.connector.run.RunTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** 실행 오케스트레이터. 부분 실패와 상태 전이를 검증한다. */
class ConnectorRunnerIntegrationTest extends IntegrationTest {

    @TempDir Path tempDir;

    @Autowired private ConnectorRunner runner;
    @Autowired private ConnectorTestFixture fixture;   // 커넥터·매핑을 만들어주는 테스트 헬퍼

    @Test
    @DisplayName("일부 행이 깨져도 나머지는 적재된다")
    void 부분_실패를_허용한다() throws Exception {
        Path csv = tempDir.resolve("mixed.csv");
        Files.writeString(csv, """
                품목코드,수량
                A-001,10
                A-002,없음
                A-003,30
                """, StandardCharsets.UTF_8);

        UUID connectorId = fixture.createSnapshotConnector();

        ConnectorRun run = runner.run(new RunRequest(connectorId, RunMode.TEST,
                RunTrigger.MANUAL, csv));

        assertThat(run.getStatus()).isEqualTo(RunStatus.PARTIAL);
        assertThat(run.getSuccessCount()).isEqualTo(2);
        assertThat(run.getFailedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("DRAFT 매핑으로 TEST 실행은 되지만 LIVE 는 막힌다")
    void DRAFT_는_테스트만_가능하다() throws Exception {
        Path csv = tempDir.resolve("ok.csv");
        Files.writeString(csv, "품목코드,수량\nA-001,10\n", StandardCharsets.UTF_8);
        UUID connectorId = fixture.createSnapshotConnector();   // 매핑은 DRAFT 로 생성된다

        ConnectorRun testRun = runner.run(new RunRequest(connectorId, RunMode.TEST,
                RunTrigger.MANUAL, csv));
        assertThat(testRun.getStatus()).isEqualTo(RunStatus.SUCCEEDED);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        runner.run(new RunRequest(connectorId, RunMode.LIVE,
                                RunTrigger.MANUAL, csv)))
                .hasFieldOrPropertyWithValue("errorCode",
                        kr.suhsaechan.palim.common.error.ErrorCode.MAPPING_NOT_ACTIVE);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-app:test --tests '*ConnectorRunnerIntegrationTest'`
Expected: 컴파일 실패

- [ ] **Step 3: 오케스트레이터 구현**

```java
package kr.suhsaechan.palim.connector.run;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 실행 요청.
 *
 * @param file UPLOAD 원천일 때의 임시 파일. 다른 유형이면 null
 */
public record RunRequest(UUID connectorId, RunMode mode, RunTrigger trigger, Path file) {
}
```

```java
package kr.suhsaechan.palim.connector.run;

import java.util.ArrayList;
import java.util.List;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.define.MappingStatus;
import kr.suhsaechan.palim.connector.transform.MappedRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 오케스트레이터.
 *
 * <p>읽기 → 드리프트 검사 → 변환 → 적재를 순서대로 돌린다. 각 단계는 독립 컴포넌트이며
 * 이 클래스는 흐름과 실패 처리만 담당한다.
 *
 * <p><b>청크 500행 단위로 커밋한다.</b> 행 단위 커밋은 대량 적재에서 느리고, 전체를 한
 * 트랜잭션에 묶으면 부분 실패를 표현할 수 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorRunner {

    private static final int CHUNK_SIZE = 500;

    private final ConnectorLoader loader;          // 커넥터·매핑·필드 조회를 모은 컴포넌트
    private final SourceReaderRegistry readers;    // SourceType → SourceReader
    private final DriftGuard driftGuard;           // 드리프트 검사 + 예외 변환
    private final RowMapper rowMapper;             // TransformEngine + UnitConverter 조합
    private final WriterSelector writerSelector;   // RunMode → RecordWriter
    private final ConnectorRunRepository runRepository;
    private final ConnectorRunErrorRepository errorRepository;

    @Transactional
    public ConnectorRun run(RunRequest request) {
        Connector connector = loader.connector(request.connectorId());
        ConnectorMapping mapping = loader.mappingFor(connector, request.mode());

        // LIVE 는 확정된 매핑에서만. DRAFT 로 실제 데이터를 넣으면 되돌릴 근거가 없다.
        if (request.mode() == RunMode.LIVE && mapping.getStatus() != MappingStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.MAPPING_NOT_ACTIVE);
        }
        if (runRepository.existsByConnectorIdAndStatus(connector.getId(), RunStatus.RUNNING)) {
            throw new BusinessException(ErrorCode.CONNECTOR_ALREADY_RUNNING);
        }

        ConnectorRun run = runRepository.save(ConnectorRun.start(
                connector.getTenantId(), connector.getId(), mapping.getId(),
                mapping.getVersion(), request.mode(), request.trigger()));

        try {
            driftGuard.verify(connector, mapping, request);

            List<MappedRow> buffer = new ArrayList<>(CHUNK_SIZE);
            int total = 0;
            int success = 0;
            int failed = 0;

            var iterator = readers.of(connector.getSourceType())
                    .read(loader.contextOf(connector, request)).iterator();

            while (iterator.hasNext()) {
                var sourceRow = iterator.next();
                total++;
                try {
                    buffer.add(rowMapper.map(connector, mapping, sourceRow));
                } catch (BusinessException e) {
                    failed++;
                    errorRepository.save(ConnectorRunError.of(connector.getTenantId(),
                            run.getId(), sourceRow.rowNumber(), sourceRow.values(),
                            e.getErrorCode().name(), e.getMessage()));
                    continue;
                }
                if (buffer.size() >= CHUNK_SIZE) {
                    success += flush(connector, run, buffer);
                }
            }
            success += flush(connector, run, buffer);

            run.finish(total, success, failed);
            if (failed == 0 && request.mode() == RunMode.LIVE) {
                loader.advanceCursor(connector, mapping);
            }
            return runRepository.save(run);

        } catch (BusinessException e) {
            log.warn("커넥터 실행 실패 — {} ({})", connector.getCode(), e.getErrorCode(), e);
            run.fail(e.getMessage());
            return runRepository.save(run);
        }
    }

    private int flush(Connector connector, ConnectorRun run, List<MappedRow> buffer) {
        if (buffer.isEmpty()) {
            return 0;
        }
        int written = writerSelector.of(run.getRunMode())
                .write(connector.getTenantId(), run.getId(), loader.targetModel(connector),
                        List.copyOf(buffer))
                .inserted();
        buffer.clear();
        return written;
    }
}
```

> 협력 컴포넌트(`ConnectorLoader` · `SourceReaderRegistry` · `DriftGuard` · `RowMapper` ·
> `WriterSelector`)는 각각 조회·선택·조합만 하는 얇은 클래스다. 오케스트레이터가 이들을 직접
> 조립하면 이 클래스가 300줄을 넘고, 그러면 흐름이 보이지 않는다.

- [ ] **Step 4: 표준 모델 필드 부트스트랩**

`target_field` 초기 데이터를 SQL 대신 코드로 등록한다. `ConfigDefinitionProvider` 와 같은
방식이며, 없는 필드만 추가하므로 재기동이 안전하다.

```java
package kr.suhsaechan.palim.connector.model;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 표준 모델의 필드 정의 등록.
 *
 * <p>마이그레이션 SQL 에 넣지 않는 이유 — 필드가 100개 가까이 되고 표시명·순서가 자주 바뀐다.
 * 코드 옆에 두면 필드 추가가 한 줄이고, 없는 것만 등록하므로 재기동이 안전하다.
 */
@Component
@RequiredArgsConstructor
public class TargetFieldBootstrap implements ApplicationRunner {

    private final TargetModelRepository modelRepository;
    private final TargetFieldRepository fieldRepository;

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        register("std_stock_snapshot", List.of(
                spec("item_ref", "품목", FieldDataType.STRING, true),
                spec("base_at", "기준 시각", FieldDataType.TIMESTAMP, true),
                spec("source", "출처", FieldDataType.STRING, true),
                spec("quantity", "수량", FieldDataType.DECIMAL, true),
                spec("unit", "단위", FieldDataType.STRING, false),
                spec("warehouse_code", "창고", FieldDataType.STRING, false),
                spec("lot_code", "로트", FieldDataType.STRING, false),
                spec("expiry_date", "유통기한", FieldDataType.DATE, false),
                spec("unit_cost", "단가", FieldDataType.DECIMAL, false),
                spec("amount", "금액", FieldDataType.DECIMAL, false),
                spec("raw_item_name", "원본 품명", FieldDataType.STRING, false)));
        // std_item · std_stock_movement · std_outbound_order 도 같은 방식으로 등록한다.
    }

    private void register(String modelCode, List<FieldSpec> specs) {
        // 구현: 모델 조회 → 기존 field_key 집합 조회 → 없는 것만 save
    }

    private FieldSpec spec(String key, String name, FieldDataType type, boolean required) {
        return new FieldSpec(key, name, type, required);
    }

    private record FieldSpec(String key, String name, FieldDataType type, boolean required) {
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-app:test --tests '*ConnectorRunnerIntegrationTest'`
Expected: PASS (2건)

- [ ] **Step 6: 커밋**

```bash
git add palim-connector/src palim-app/src/test/java
git commit -m "범용 데이터 연동 프레임워크와 물품 표준 모델 : feat : 실행 오케스트레이터와 표준 필드 부트스트랩 추가 https://github.com/Cassiiopeia/palim/issues/53"
```

---

## Task 12: LIVE 되돌리기와 E2E 통합 테스트

**Files:**
- Modify: `.../write/StandardModelWriter.java` (rollback 구현)
- Create: `.../run/RollbackService.java`
- Create: `palim-app/src/test/java/kr/suhsaechan/palim/integration/ConnectorPipelineIntegrationTest.java`

**Interfaces:**
- Consumes: Task 4~11 전부
- Produces: `RollbackService.rollbackLatest(UUID tenantId, UUID connectorId)` → `ConnectorRun`

- [ ] **Step 1: E2E 테스트 작성**

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.run.ConnectorRunner;
import kr.suhsaechan.palim.connector.run.RollbackService;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.run.RunRequest;
import kr.suhsaechan.palim.connector.run.RunTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 파이프라인 E2E.
 *
 * <p>설계가 지목한 핵심 두 가지를 검증한다 — <b>되돌리기가 정확히 그만큼만 지우는가</b>,
 * <b>재실행에 중복이 생기지 않는가</b>. 여기가 깨지면 사람이 시스템을 믿지 못한다.
 */
class ConnectorPipelineIntegrationTest extends IntegrationTest {

    @TempDir Path tempDir;

    @Autowired private ConnectorRunner runner;
    @Autowired private RollbackService rollbackService;
    @Autowired private ConnectorTestFixture fixture;
    @Autowired private JdbcClient jdbcClient;

    @Test
    @DisplayName("업로드부터 적재까지 전 과정이 돈다")
    void 전_과정이_동작한다() throws Exception {
        Path csv = write("품목코드,수량\nA-001,10\nA-002,20\n");
        UUID connectorId = fixture.createActiveSnapshotConnector();

        var run = runner.run(new RunRequest(connectorId, RunMode.LIVE, RunTrigger.MANUAL, csv));

        assertThat(run.getSuccessCount()).isEqualTo(2);
        assertThat(countSnapshots()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 파일을 두 번 넣어도 행이 늘지 않는다")
    void 재실행이_중복을_만들지_않는다() throws Exception {
        Path csv = write("품목코드,수량\nA-001,10\n");
        UUID connectorId = fixture.createActiveSnapshotConnector();

        runner.run(new RunRequest(connectorId, RunMode.LIVE, RunTrigger.MANUAL, csv));
        runner.run(new RunRequest(connectorId, RunMode.LIVE, RunTrigger.MANUAL, csv));

        assertThat(countSnapshots()).isEqualTo(1);
    }

    @Test
    @DisplayName("가장 최근 LIVE 실행만 되돌릴 수 있다")
    void 최근_실행만_되돌린다() throws Exception {
        Path csv = write("품목코드,수량\nA-001,10\n");
        UUID connectorId = fixture.createActiveSnapshotConnector();
        var first = runner.run(new RunRequest(connectorId, RunMode.LIVE, RunTrigger.MANUAL, csv));
        runner.run(new RunRequest(connectorId, RunMode.LIVE, RunTrigger.MANUAL, csv));

        assertThatThrownBy(() -> rollbackService.rollback(first.getTenantId(), first.getId()))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROLLBACK_NOT_ALLOWED);
    }

    @Test
    @DisplayName("되돌리면 이번 실행이 만든 행이 사라진다")
    void 되돌리기가_정확히_지운다() throws Exception {
        Path csv = write("품목코드,수량\nA-001,10\n");
        UUID connectorId = fixture.createActiveSnapshotConnector();
        var run = runner.run(new RunRequest(connectorId, RunMode.LIVE, RunTrigger.MANUAL, csv));

        rollbackService.rollback(run.getTenantId(), run.getId());

        assertThat(countSnapshots()).isZero();
    }

    private Path write(String content) throws Exception {
        Path csv = tempDir.resolve("data-" + UUID.randomUUID() + ".csv");
        Files.writeString(csv, content, StandardCharsets.UTF_8);
        return csv;
    }

    private int countSnapshots() {
        return jdbcClient.sql("SELECT count(*)::int FROM std_stock_snapshot")
                .query(Integer.class).single();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew :palim-app:test --tests '*ConnectorPipelineIntegrationTest'`
Expected: 컴파일 실패 — `RollbackService` 없음

- [ ] **Step 3: 되돌리기 구현**

```java
package kr.suhsaechan.palim.connector.run;

import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 되돌리기.
 *
 * <p>LIVE 는 <b>가장 최근 실행 하나</b>만 되돌린다. 그 이전까지 거슬러 오르면 이후 실행들과
 * 뒤엉켜 어떤 상태로 돌아가는지 아무도 설명할 수 없게 된다. TEST 는 스테이징만 지우므로
 * 제한이 없다.
 */
@Service
@RequiredArgsConstructor
public class RollbackService {

    private final ConnectorRunRepository runRepository;
    private final JdbcClient jdbcClient;

    @Transactional
    public ConnectorRun rollback(UUID tenantId, UUID runId) {
        ConnectorRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTOR_NOT_FOUND));

        if (!run.isTest() && !isLatestLive(run)) {
            throw new BusinessException(ErrorCode.ROLLBACK_NOT_ALLOWED);
        }

        if (run.isTest()) {
            jdbcClient.sql("DELETE FROM connector_staging WHERE run_id = :runId")
                    .param("runId", runId).update();
        } else {
            restoreFromUndoLog(runId);
        }

        run.markRolledBack();
        return runRepository.save(run);
    }

    private boolean isLatestLive(ConnectorRun run) {
        return runRepository
                .findFirstByConnectorIdAndRunModeOrderByStartedAtDesc(
                        run.getConnectorId(), RunMode.LIVE)
                .map(latest -> latest.getId().equals(run.getId()))
                .orElse(false);
    }

    /**
     * 되돌리기.
     *
     * <p>{@code previous_row} 가 NULL 이면 이번 실행이 처음 만든 행이므로 삭제한다. 값이
     * 있으면 그 값으로 되돌린다. 실행 순서의 역순으로 처리해야 같은 행을 여러 번 건드린
     * 경우에도 최초 상태로 정확히 돌아간다.
     */
    private void restoreFromUndoLog(UUID runId) {
        jdbcClient.sql("""
                        DELETE FROM std_stock_snapshot s
                        USING connector_undo_log u
                        WHERE u.run_id = :runId
                          AND u.previous_row IS NULL
                          AND u.table_name = 'std_stock_snapshot'
                          AND s.run_id = :runId
                        """)
                .param("runId", runId).update();

        // previous_row 가 있는 행 복원은 jsonb_populate_record 로 되돌린다.
        jdbcClient.sql("""
                        UPDATE std_stock_snapshot s
                        SET quantity = (u.previous_row ->> 'quantity')::numeric,
                            base_quantity = (u.previous_row ->> 'base_quantity')::numeric,
                            run_id = (u.previous_row ->> 'run_id')::uuid,
                            updated_at = now()
                        FROM connector_undo_log u
                        WHERE u.run_id = :runId
                          AND u.previous_row IS NOT NULL
                          AND u.table_name = 'std_stock_snapshot'
                          AND s.id = (u.previous_row ->> 'id')::uuid
                        """)
                .param("runId", runId).update();

        jdbcClient.sql("DELETE FROM connector_undo_log WHERE run_id = :runId")
                .param("runId", runId).update();
    }
}
```

- [ ] **Step 4: 전체 테스트 실행**

Run: `JAVA_HOME=$(brew --prefix openjdk@25) ./gradlew build`
Expected: BUILD SUCCESSFUL — 단위 테스트와 통합 테스트 전부 통과

- [ ] **Step 5: 커밋**

```bash
git add palim-connector/src palim-app/src/test/java
git commit -m "범용 데이터 연동 프레임워크와 물품 표준 모델 : feat : 실행 되돌리기와 파이프라인 통합 테스트 추가 https://github.com/Cassiiopeia/palim/issues/53"
```

- [ ] **Step 6: 문서 갱신**

`docs/07-DECISIONS.md` 에 항목을 추가한다.

```markdown
## 026. 연동 정의를 데이터로 둔다 (#53)

외부 원천마다 Java 코드를 쓰면 새 원천이 붙을 때마다 배포가 필요하고, 양식이 바뀌면 코드를
고쳐야 한다. 연동 정의(원천·매핑·변환)를 DB 행으로 두어 화면에서 만들게 했다.

런타임 DDL 은 쓰지 않는다. 기본 제공 모델은 정식 테이블(`std_*`), 커스텀 모델은 JSONB 다.
DDL 을 실행하면 Flyway 와 충돌하고 앱 계정에 DDL 권한이 필요해진다.

TEST 실행은 스테이징에만 쓴다. 표준 테이블에 넣고 지우는 방식은 UPSERT 와 양립하지 않고
(덮어쓰면 행의 소유 실행이 바뀐다), 지우기 전에 도메인 로직이 읽으면 오염된 결과가 나온다.
```

`docs/02-ARCHITECTURE.md` 의 「활성 모듈」 표에 `palim-connector` 를 추가한다.

```bash
git add docs/07-DECISIONS.md docs/02-ARCHITECTURE.md
git commit -m "범용 데이터 연동 프레임워크와 물품 표준 모델 : docs : 아키텍처와 결정 기록 갱신 https://github.com/Cassiiopeia/palim/issues/53"
```

---

## Phase 1 이후

이 계획의 범위 밖이며 각각 별도 계획으로 만든다.

| Phase | 내용 |
|---|---|
| **2. 화면** | 커넥터 목록 · 8단계 위저드 · 매핑 편집기 · 미리보기 · 실행 이력 |
| **3. 자동화** | cron 스케줄러 · 증분 커서 실전 적용 · `HttpApiSourceReader` · 실패 알림(Outbox) |
| **4. AI 보조** | 매핑 초안 생성(구조화 출력) · 신뢰도 표시 |
| **5. 도메인** | 재고 정합성 대사(#49) — 표준 모델 위에 얹는다 |

## 자체 리뷰 결과

**스펙 커버리지** — 설계 문서 각 장을 태스크에 대응시켰다.

| 설계 | 태스크 |
|---|---|
| 3장 모듈 구조 | Task 1 |
| 4-1 원천 어댑터 | Task 6 |
| 6장 데이터 모델 | Task 2 · 4 |
| 7장 표준 모델 | Task 3 · 11(필드 등록) |
| 8-1 멀티테넌시 | Task 2(컬럼) — **Hibernate 필터 적용은 Phase 2 로 미룸**(화면이 있어야 세션 컨텍스트가 생긴다) |
| 8-2 단위 환산 | Task 8 |
| 8-3 증분·자연키 | Task 9(자연키) · Task 11(커서 전진) |
| 9-1 TEST/LIVE 분리 | Task 10 · 12 |
| 9-2 드리프트 | Task 7 |
| 9-3 부분 실패·청크 | Task 11 |
| 9-4 동시 실행 차단 | Task 11 + Task 4 의 부분 유니크 인덱스 |
| 11장 후처리 | **Phase 2 로 미룸** — 선언적 규칙은 Task 9 에 있고, py 훅은 화면이 있어야 지정할 수 있다 |
| 13장 예외 | Task 1 |
| 14장 테스트 | 각 태스크 + Task 12 |

**미룬 것 2가지를 명시한다**: 멀티테넌시 필터 적용(컬럼과 인덱스는 지금 넣는다)과 py 훅
실행기. 둘 다 화면 계층이 전제이며, 지금 만들면 호출하는 곳이 없어 검증할 수 없다.

**타입 일관성** — `MappedRow` · `SourceRow` · `ConvertedQuantity` · `WriteResult` 의 필드명이
태스크 간에 동일한지 확인했다. `RecordWriter.write` 시그니처가 Task 10 과 11 에서 일치한다.
