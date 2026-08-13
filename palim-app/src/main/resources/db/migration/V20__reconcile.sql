-- 재고 정합성 대조.
--
-- 두 원천의 재고를 같은 기준으로 묶어 비교하고, 시간 탓으로 설명되지 않는 차이만 남긴다.
-- 운영 DB 가 PostgreSQL 14 이므로 NULLS NOT DISTINCT(15+) · MERGE(15+) 를 쓰지 않는다.

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
