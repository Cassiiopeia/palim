-- Palim 초기 스키마
--
-- 설계 원칙
--   · 기본키는 UUIDv7 을 애플리케이션에서 생성해 넣는다. DB 기본값을 두지 않는다.
--   · 모든 시각 컬럼은 timestamptz 다. 채널 API 가 KST/UTC 를 섞어 응답하므로
--     타임존 모호성이 유입되면 중복 판정과 수집 커서가 어긋나고 재고가 이중 차감된다.
--   · 금액은 bigint(원 단위). 원화는 소수점이 없다.
--   · 도메인 모듈은 코드 차원에서 서로를 의존하지 않지만, 외래키는 정상 부여한다.
--     모듈 독립성과 데이터 정합성은 별개 문제다.

-- ============================================================
-- SKU · 재고
-- ============================================================

CREATE TABLE sku
(
    id               uuid         NOT NULL,
    code             varchar(50)  NOT NULL,
    name             varchar(200) NOT NULL,
    quantity         integer      NOT NULL,
    safety_threshold integer      NOT NULL,
    active           boolean      NOT NULL,
    version          bigint,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_sku PRIMARY KEY (id),
    CONSTRAINT uk_sku_code UNIQUE (code)
);

COMMENT ON TABLE sku IS 'SKU 와 현재 재고 스냅샷. 재고의 유일한 기준';
COMMENT ON COLUMN sku.active IS '단종 여부. 소프트 삭제가 아니다';

-- 재고 변동 이력. append-only 이며 수정·삭제하지 않는다.
CREATE TABLE stock_movement
(
    id             uuid        NOT NULL,
    sku_id         uuid        NOT NULL,
    delta          integer     NOT NULL,
    reason         varchar(30) NOT NULL,
    quantity_after integer     NOT NULL,
    reference_type varchar(30),
    reference_id   uuid,
    memo           varchar(500),
    version        bigint,
    created_at     timestamptz,
    updated_at     timestamptz,
    CONSTRAINT pk_stock_movement PRIMARY KEY (id),
    CONSTRAINT fk_stock_movement_sku FOREIGN KEY (sku_id) REFERENCES sku (id)
);

COMMENT ON COLUMN stock_movement.delta IS '음수는 차감, 양수는 증가';
COMMENT ON COLUMN stock_movement.quantity_after IS '변동 직후 재고. 이력만으로 당시 상태를 추적하기 위함';

-- 대조 배치가 SUM(delta) 를 SKU 단위로 집계한다(설계서 5.3).
CREATE INDEX ix_stock_movement_sku_created ON stock_movement (sku_id, created_at DESC);

-- ============================================================
-- 주문
-- ============================================================

CREATE TABLE orders
(
    id                uuid         NOT NULL,
    channel_code      varchar(20)  NOT NULL,
    channel_order_no  varchar(100) NOT NULL,
    ordered_at        timestamptz  NOT NULL,
    collected_at      timestamptz  NOT NULL,
    buyer_name        varchar(100),
    total_amount      bigint       NOT NULL,
    status            varchar(20)  NOT NULL,
    version           bigint,
    created_at        timestamptz,
    updated_at        timestamptz,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uk_orders_channel_order_no UNIQUE (channel_code, channel_order_no)
);

COMMENT ON TABLE orders IS 'order 가 SQL 예약어이므로 테이블명은 orders';
COMMENT ON COLUMN orders.ordered_at IS '채널이 알려준 주문 시각';
COMMENT ON COLUMN orders.collected_at IS '수집 시각. 주문 시각과의 차이가 알림 지연이다';

CREATE INDEX ix_orders_ordered_at ON orders (ordered_at DESC);

CREATE TABLE order_line
(
    id                   uuid         NOT NULL,
    order_id             uuid         NOT NULL,
    channel_code         varchar(20)  NOT NULL,
    channel_order_no     varchar(100) NOT NULL,
    channel_line_no      varchar(100) NOT NULL,
    channel_product_no   varchar(100) NOT NULL,
    channel_option_no    varchar(100),
    channel_product_name varchar(300) NOT NULL,
    sku_id               uuid,
    quantity             integer      NOT NULL,
    unit_price           bigint       NOT NULL,
    amount               bigint       NOT NULL,
    stock_applied        boolean      NOT NULL,
    version              bigint,
    created_at           timestamptz,
    updated_at           timestamptz,
    CONSTRAINT pk_order_line PRIMARY KEY (id),
    -- 중복 수집을 막는 유일한 방어선이다. "조회 후 없으면 삽입"은 수집이 중첩되는 순간 뚫린다.
    -- 재고 차감은 이 제약을 통과해 실제로 INSERT 된 경우에만 수행한다(A-02, 설계서 5.1).
    CONSTRAINT uk_order_line_channel UNIQUE (channel_code, channel_order_no, channel_line_no),
    CONSTRAINT fk_order_line_order FOREIGN KEY (order_id) REFERENCES orders (id),
    -- nullable FK — 매핑되지 않은 상품의 주문도 저장해야 한다(F-04).
    CONSTRAINT fk_order_line_sku FOREIGN KEY (sku_id) REFERENCES sku (id)
);

COMMENT ON COLUMN order_line.channel_code IS '유니크 제약을 라인 단위로 걸기 위한 비정규화';
COMMENT ON COLUMN order_line.sku_id IS '미매핑 주문은 NULL. 매핑 후 소급 반영한다';
COMMENT ON COLUMN order_line.stock_applied IS '재고 반영 여부. 소급 반영 대상 판별에 쓴다';

CREATE INDEX ix_order_line_order ON order_line (order_id);
CREATE INDEX ix_order_line_sku ON order_line (sku_id);

-- 미매핑 주문 조회 — 매핑 필요 알림 대상(F-04).
CREATE INDEX ix_order_line_unmapped ON order_line (created_at DESC) WHERE sku_id IS NULL;

-- 매핑은 됐으나 재고 미반영 — 소급 반영 실행 대상.
CREATE INDEX ix_order_line_awaiting_stock ON order_line (created_at) WHERE sku_id IS NOT NULL AND stock_applied = false;

-- ============================================================
-- 상품 매핑
-- ============================================================

CREATE TABLE product_mapping
(
    id                   uuid         NOT NULL,
    channel_code         varchar(20)  NOT NULL,
    channel_product_no   varchar(100) NOT NULL,
    channel_option_no    varchar(100),
    channel_product_name varchar(300) NOT NULL,
    sku_id               uuid         NOT NULL,
    active               boolean      NOT NULL,
    version              bigint,
    created_at           timestamptz,
    updated_at           timestamptz,
    CONSTRAINT pk_product_mapping PRIMARY KEY (id),
    CONSTRAINT fk_product_mapping_sku FOREIGN KEY (sku_id) REFERENCES sku (id)
);

-- channel_option_no 가 NULL 일 수 있다. PostgreSQL 은 NULL 을 서로 다른 값으로 취급하므로
-- 일반 UNIQUE 제약으로는 옵션 없는 상품의 중복 매핑이 걸러지지 않는다.
-- COALESCE 표현식 인덱스로 NULL 을 빈 문자열과 동일하게 다룬다.
CREATE UNIQUE INDEX uk_product_mapping_channel_product
    ON product_mapping (channel_code, channel_product_no, COALESCE(channel_option_no, ''));

CREATE INDEX ix_product_mapping_sku ON product_mapping (sku_id);

-- ============================================================
-- 채널
-- ============================================================

CREATE TABLE channel
(
    id                        uuid        NOT NULL,
    code                      varchar(20) NOT NULL,
    name                      varchar(50) NOT NULL,
    enabled                   boolean     NOT NULL,
    collect_interval_seconds  integer     NOT NULL,
    collected_until           timestamptz,
    last_collected_at         timestamptz,
    last_collect_status       varchar(20),
    last_collect_error        varchar(1000),
    consecutive_failure_count integer     NOT NULL,
    version                   bigint,
    created_at                timestamptz,
    updated_at                timestamptz,
    CONSTRAINT pk_channel PRIMARY KEY (id),
    CONSTRAINT uk_channel_code UNIQUE (code)
);

COMMENT ON COLUMN channel.collected_until IS '수집 커서. 다음 수집은 여유를 두고 겹쳐 조회한다';
COMMENT ON COLUMN channel.consecutive_failure_count IS '연속 실패. 임계 도달 시 경고 후 수집 중단(A-10)';

CREATE TABLE channel_credential
(
    id              uuid          NOT NULL,
    channel_id      uuid          NOT NULL,
    credential_key  varchar(50)   NOT NULL,
    encrypted_value varchar(2000) NOT NULL,
    version         bigint,
    created_at      timestamptz,
    updated_at      timestamptz,
    CONSTRAINT pk_channel_credential PRIMARY KEY (id),
    CONSTRAINT uk_channel_credential_key UNIQUE (channel_id, credential_key),
    CONSTRAINT fk_channel_credential_channel FOREIGN KEY (channel_id) REFERENCES channel (id)
);

COMMENT ON TABLE channel_credential IS '채널마다 인증 필드가 달라 key-value 로 저장한다';
COMMENT ON COLUMN channel_credential.encrypted_value IS 'AES-GCM 암호문. 평문 저장 금지';

-- 채널 재고 전송 이력. 채널에 기록하는 유일한 경로이므로 전후 값을 모두 남긴다(F-08).
CREATE TABLE stock_push_log
(
    id                 uuid         NOT NULL,
    channel_code       varchar(20)  NOT NULL,
    sku_id             uuid         NOT NULL,
    channel_product_no varchar(100) NOT NULL,
    before_quantity    integer,
    after_quantity     integer      NOT NULL,
    simulated          boolean      NOT NULL,
    status             varchar(20)  NOT NULL,
    error_message      varchar(1000),
    version            bigint,
    created_at         timestamptz,
    updated_at         timestamptz,
    CONSTRAINT pk_stock_push_log PRIMARY KEY (id),
    CONSTRAINT fk_stock_push_log_sku FOREIGN KEY (sku_id) REFERENCES sku (id)
);

COMMENT ON COLUMN stock_push_log.simulated IS 'true 면 실제 전송하지 않았다';
COMMENT ON COLUMN stock_push_log.status IS 'BLOCKED 는 변동량 상한 초과로 차단된 경우';

CREATE INDEX ix_stock_push_log_sku_created ON stock_push_log (sku_id, created_at DESC);
CREATE INDEX ix_stock_push_log_channel_created ON stock_push_log (channel_code, created_at DESC);

-- 전송 안전장치. 단일 행으로 관리한다(F-08).
CREATE TABLE stock_push_setting
(
    id                 uuid    NOT NULL,
    enabled            boolean NOT NULL,
    simulation_mode    boolean NOT NULL,
    max_delta_per_push integer NOT NULL,
    version            bigint,
    created_at         timestamptz,
    updated_at         timestamptz,
    CONSTRAINT pk_stock_push_setting PRIMARY KEY (id)
);

COMMENT ON TABLE stock_push_setting IS '전체 중단 스위치·시뮬레이션 모드·변동량 상한';

-- ============================================================
-- 알림
-- ============================================================

-- 발송 대기. 주문 저장과 같은 트랜잭션에서 삽입되므로 큐가 유실돼도 복구된다(A-14).
CREATE TABLE notification_outbox
(
    id            uuid        NOT NULL,
    type          varchar(30) NOT NULL,
    payload       text        NOT NULL,
    status        varchar(20) NOT NULL,
    attempt_count integer     NOT NULL,
    last_error    varchar(1000),
    sent_at       timestamptz,
    version       bigint,
    created_at    timestamptz,
    updated_at    timestamptz,
    CONSTRAINT pk_notification_outbox PRIMARY KEY (id)
);

-- relay 가 PENDING 을 오래된 순으로 읽는다. 적체 감시도 같은 인덱스를 쓴다.
CREATE INDEX ix_notification_outbox_status_created ON notification_outbox (status, created_at);

-- 알림 설정. 단일 행으로 관리하며 웹에서 변경하면 재시작 없이 반영된다(F-02).
CREATE TABLE notification_setting
(
    id                     uuid        NOT NULL,
    telegram_chat_id       varchar(50),
    order_alert_mode       varchar(20) NOT NULL,
    batch_interval_minutes integer     NOT NULL,
    quiet_hours_start      time,
    quiet_hours_end        time,
    daily_report_enabled   boolean     NOT NULL,
    daily_report_time      time        NOT NULL,
    low_stock_repeat_hours integer     NOT NULL,
    version                bigint,
    created_at             timestamptz,
    updated_at             timestamptz,
    CONSTRAINT pk_notification_setting PRIMARY KEY (id)
);

COMMENT ON COLUMN notification_setting.quiet_hours_start IS '하루 중 시점이라 time 을 쓴다. Instant 규칙의 의도적 예외';

-- ============================================================
-- 인증
-- ============================================================

CREATE TABLE admin_account
(
    id            uuid         NOT NULL,
    username      varchar(50)  NOT NULL,
    password_hash varchar(200) NOT NULL,
    enabled       boolean      NOT NULL,
    version       bigint,
    created_at    timestamptz,
    updated_at    timestamptz,
    CONSTRAINT pk_admin_account PRIMARY KEY (id),
    CONSTRAINT uk_admin_account_username UNIQUE (username)
);

COMMENT ON TABLE admin_account IS '관리자 계정 1개. 다중 사용자·권한 분리는 범위 외(F-09)';
