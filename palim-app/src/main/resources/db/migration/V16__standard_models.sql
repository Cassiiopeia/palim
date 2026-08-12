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
    id                 uuid            NOT NULL,
    tenant_id          uuid            NOT NULL,
    run_id             uuid,
    -- 식별
    item_code          varchar(100)    NOT NULL,
    item_name          varchar(255)    NOT NULL,
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
    is_active          boolean         NOT NULL DEFAULT true,
    discontinued_at    timestamptz,
    -- 표준에 없는 원천 컬럼을 버리지 않는다. 과거 시점 데이터는 다시 받을 수 없다.
    attributes         jsonb           NOT NULL DEFAULT '{}'::jsonb,
    created_at         timestamptz,
    updated_at         timestamptz,
    CONSTRAINT pk_std_item PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_std_item_natural ON std_item (tenant_id, item_code);
CREATE INDEX ix_std_item_run ON std_item (run_id);

CREATE TABLE std_stock_snapshot
(
    id                 uuid           NOT NULL,
    tenant_id          uuid           NOT NULL,
    run_id             uuid,
    item_ref           varchar(255)   NOT NULL,
    -- 시점. 두 원천을 다른 시각에 뽑으면 그 사이 출고분만큼 무조건 차이가 난다.
    base_at            timestamptz    NOT NULL,
    source             varchar(50)    NOT NULL,
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
    -- 수량. 원천 단위가 제각각이므로 집계는 base_quantity 만 쓴다.
    quantity           numeric(19, 3) NOT NULL,
    unit               varchar(20),
    base_quantity      numeric(19, 3) NOT NULL,
    base_unit          varchar(20)    NOT NULL,
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
    attributes         jsonb          NOT NULL DEFAULT '{}'::jsonb,
    created_at         timestamptz,
    updated_at         timestamptz,
    CONSTRAINT pk_std_stock_snapshot PRIMARY KEY (id)
);
-- 자연키. 같은 구간을 두 번 가져와도 중복이 생기지 않는다.
-- 창고·로트가 없는 원천이 흔하므로 NULLS NOT DISTINCT 가 없으면 NULL != NULL 이라
-- 재실행마다 같은 행이 새로 쌓인다 (PostgreSQL 15+).
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
-- 전표번호·로트가 비는 원천이 있으므로 여기도 NULLS NOT DISTINCT 가 필요하다.
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
-- 단품 주문은 행번호가 없어 NULL 이다. 그 주문이 재수집마다 중복되지 않아야 한다.
CREATE UNIQUE INDEX ux_std_outbound_order_natural
    ON std_outbound_order (tenant_id, order_no, order_line_no, item_ref) NULLS NOT DISTINCT;
CREATE INDEX ix_std_outbound_order_run ON std_outbound_order (run_id);

-- 표준 모델 4종을 target_model 에 등록한다. 화면이 이 목록에서 고른다.
-- target_field 초기 데이터는 여기 넣지 않는다 — 필드가 100개 가까이 되고 표시명·순서가
-- 자주 바뀌므로, 코드 옆에서 관리되는 부트스트랩 컴포넌트가 등록한다.
INSERT INTO target_model (id, tenant_id, code, name, kind, storage, table_name,
                          natural_key_fields, created_at, updated_at)
VALUES ('00000000-0000-7000-8000-000000000010', '00000000-0000-7000-8000-000000000001',
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
