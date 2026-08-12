-- ============================================================
-- 범용 데이터 연동 프레임워크 — 정의·실행 계층 (#53)
--
-- 연동 정의를 코드가 아니라 데이터로 둔다. 새 원천이 붙어도 배포가 필요 없고,
-- 원천 양식이 바뀌면 매핑 버전을 올린다.
--
-- 모든 테이블에 tenant_id 를 둔다. 지금은 기본 테넌트 하나로 운영하지만,
-- 나중에 넣으려면 전 테이블 컬럼 추가 + 전 쿼리 수정 + 데이터 소급이 필요하다.
-- 지금은 컬럼 하나와 인덱스 하나 값이다.
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

-- ------------------------------------------------------------
-- 정의 계층
-- ------------------------------------------------------------

-- 목표 모델. BUILTIN 은 정식 테이블에, CUSTOM 은 custom_record JSONB 에 적재된다.
-- 런타임 DDL 을 쓰지 않으므로 커스텀 모델이 아무리 늘어도 테이블 수는 그대로다.
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

-- 연동 하나. 원천 접속 정보의 비밀값은 여기 두지 않고 참조만 남긴다 —
-- 이 저장소는 PUBLIC 이며 DB 덤프가 유출돼도 키가 새지 않아야 한다.
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
    -- 단위 컬럼이 없는 원천의 기준 단위. 실측한 두 원천 모두 단위 컬럼이 없다.
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
-- 버전 없이 정의를 덮어쓰면 "지난달 데이터가 왜 이런가"에 답할 방법이 사라진다.
CREATE TABLE connector_mapping
(
    id            uuid        NOT NULL,
    tenant_id     uuid        NOT NULL,
    connector_id  uuid        NOT NULL,
    version       integer     NOT NULL,
    status        varchar(20) NOT NULL,
    -- 확정 당시의 원천 필드 목록. 매 실행마다 이것과 대조해 드리프트를 잡는다.
    source_schema jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- py 훅 정의. 선언적 규칙으로 안 되는 커스텀만 여기 온다.
    hooks         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    activated_at  timestamptz,
    created_at    timestamptz,
    updated_at    timestamptz,
    CONSTRAINT pk_connector_mapping PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_connector_mapping_version ON connector_mapping (connector_id, version);
-- 커넥터당 ACTIVE 는 하나뿐이다. 애플리케이션 검증에 맡기면 동시 요청에서 뚫리므로
-- 부분 유니크 인덱스로 DB 가 보장한다.
CREATE UNIQUE INDEX ux_connector_mapping_active
    ON connector_mapping (connector_id) WHERE status = 'ACTIVE';

CREATE TABLE connector_field_map
(
    id               uuid         NOT NULL,
    tenant_id        uuid         NOT NULL,
    mapping_id       uuid         NOT NULL,
    source_field     varchar(255) NOT NULL,
    target_field_key varchar(63)  NOT NULL,
    -- 값 변환 규칙. {"type":"DATE_FORMAT","params":{"pattern":"yyyy-MM-dd"}} 형태.
    transform_rule   jsonb        NOT NULL DEFAULT '{}'::jsonb,
    sort_order       integer      NOT NULL DEFAULT 0,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_connector_field_map PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_connector_field_map_target
    ON connector_field_map (mapping_id, target_field_key);
CREATE INDEX ix_connector_field_map_mapping ON connector_field_map (mapping_id, sort_order);

-- 단위 환산. item_ref 가 빈 문자열이면 전역 규칙이다.
--
-- "전역"을 NULL 이 아니라 빈 문자열로 표현하는 이유 — 유니크 인덱스에서 NULL 은 서로 다른
-- 값으로 취급되어(NULL != NULL) 전역 규칙이 무한히 중복 등록된다. PostgreSQL 15 의
-- NULLS NOT DISTINCT 로 막을 수 있지만 운영 DB 가 14 라 쓸 수 없고, 애초에 이 컬럼에서
-- "값 없음"과 "빈 값"을 구분할 실익이 없다. NOT NULL 로 두면 DB 버전을 타지 않는다.
CREATE TABLE unit_conversion
(
    id         uuid           NOT NULL,
    tenant_id  uuid           NOT NULL,
    item_ref   varchar(255)   NOT NULL DEFAULT '',
    from_unit  varchar(20)    NOT NULL,
    to_unit    varchar(20)    NOT NULL,
    factor     numeric(19, 6) NOT NULL,
    created_at timestamptz,
    updated_at timestamptz,
    CONSTRAINT pk_unit_conversion PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_unit_conversion
    ON unit_conversion (tenant_id, item_ref, from_unit, to_unit);

-- ------------------------------------------------------------
-- 실행 계층
-- ------------------------------------------------------------

-- 실행 1건. mapping_version 을 값으로 박아 두어 정의가 바뀌어도 과거 실행을 설명할 수 있다.
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
-- 동시 실행 차단. RUNNING 은 커넥터당 하나뿐이다 — cron 과 수동 실행이 겹치는 순간은
-- 반드시 오고, 겹치면 같은 원천을 두 번 적재한다.
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
-- 커스텀 모델은 컬럼이 없어 payload 안을 조건으로 찾는 것 외에 방법이 없다.
CREATE INDEX ix_custom_record_payload ON custom_record USING gin (payload);

-- LIVE 되돌리기용. UPSERT 직전 값을 남긴다. 가장 최근 실행 하나만 되돌릴 수 있다.
CREATE TABLE connector_undo_log
(
    id           uuid         NOT NULL,
    tenant_id    uuid         NOT NULL,
    run_id       uuid         NOT NULL,
    table_name   varchar(63)  NOT NULL,
    natural_key  varchar(500) NOT NULL,
    -- NULL 이면 그 행은 이번 실행이 처음 만든 것이므로 되돌리기는 삭제다.
    previous_row jsonb,
    created_at   timestamptz,
    updated_at   timestamptz,
    CONSTRAINT pk_connector_undo_log PRIMARY KEY (id)
);
CREATE INDEX ix_connector_undo_log_run ON connector_undo_log (run_id);
-- 같은 자연키의 undo 는 실행당 하나만 남긴다.
--
-- 원천 파일에 같은 키가 두 번 나오는 것은 흔한 일이고, 그대로 두면 두 번째 undo 행의
-- previous_row 는 "이번 실행이 방금 쓴 값"이 된다. 복원하면 최초 상태가 아니라 중간 상태로
-- 되돌아가며, 되돌리기를 신뢰할 수 없게 된다. 기록 시 ON CONFLICT DO NOTHING 으로 첫 값만 남긴다.
CREATE UNIQUE INDEX ux_connector_undo_log_key
    ON connector_undo_log (run_id, table_name, natural_key);
