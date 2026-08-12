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
-- 전역 규칙(item_ref IS NULL)과 품목별 규칙이 공존한다. NULLS NOT DISTINCT 가 없으면
-- NULL != NULL 이라 전역 규칙이 무한히 중복 등록된다 (PostgreSQL 15+).
CREATE UNIQUE INDEX ux_unit_conversion
    ON unit_conversion (tenant_id, item_ref, from_unit, to_unit) NULLS NOT DISTINCT;
