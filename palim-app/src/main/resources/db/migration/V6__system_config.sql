-- ============================================================
-- 범용 시스템 설정 저장소
--
-- 설정값을 배포 산출물(YAML)에 두면 값 하나 바꾸는 데 재배포가 필요하다. 운영에서는 쓸 수 없다.
-- YAML 은 "최초 1회 채워 넣는 기본값" 으로만 쓰고, 이후의 원본은 이 테이블이다.
-- 화면에서 수정하면 즉시 반영되며 재기동이 필요 없다.
-- ============================================================

CREATE TABLE system_config
(
    id           uuid         NOT NULL,
    config_key   varchar(200) NOT NULL,
    config_value jsonb        NOT NULL,
    value_type   varchar(20)  NOT NULL,
    category     varchar(50)  NOT NULL,
    display_name varchar(200) NOT NULL,
    description  varchar(1000),
    -- 화면 노출·편집 가능 여부. 내부 상태값(커서 등)을 같은 테이블에 두되 감추기 위한 장치다.
    editable     boolean      NOT NULL,
    -- 숫자형 설정의 허용 범위. 배점에 음수나 터무니없는 값이 들어가면 점수 체계가 무너진다.
    min_value    numeric(18, 4),
    max_value    numeric(18, 4),
    -- 화면 정렬 순서. 같은 카테고리 안에서 논리적 순서대로 보여준다.
    sort_order   integer      NOT NULL,
    updated_by   varchar(50),
    version      bigint,
    created_at   timestamptz,
    updated_at   timestamptz,
    CONSTRAINT pk_system_config PRIMARY KEY (id)
);

CREATE UNIQUE INDEX ux_system_config_key ON system_config (config_key);

-- 설정 화면이 카테고리별로 읽는다.
CREATE INDEX ix_system_config_category ON system_config (category, sort_order);

-- 설정 변경은 시스템 동작을 바꾸므로 누가 언제 무엇을 바꿨는지 남긴다.
-- 점수가 갑자기 달라졌을 때 원인을 추적하는 유일한 단서이며, 되돌리기의 근거다.
CREATE TABLE system_config_history
(
    id         uuid         NOT NULL,
    config_key varchar(200) NOT NULL,
    old_value  jsonb,
    new_value  jsonb        NOT NULL,
    changed_by varchar(50),
    changed_at timestamptz  NOT NULL,
    created_at timestamptz,
    updated_at timestamptz,
    CONSTRAINT pk_system_config_history PRIMARY KEY (id)
);

CREATE INDEX ix_system_config_history_key ON system_config_history (config_key, changed_at DESC);
