-- 한 번 연결한 칸을 기억한다.
--
-- 자동 추천의 근거 중 «우리가 아는 이름» 사전은 우리가 미리 적어 둔 것만 잡는다. 다음에 붙일
-- 시스템의 칸 이름은 알 수 없으므로 그것만으로는 새 원천을 감당하지 못한다.
--
-- 사람이 STOCK_BALANCE 를 「수량」에 한 번 연결하면 그 사실을 여기 남긴다. 다음에 같은 이름이
-- 오면 시스템이 먼저 골라 둔다 — 우리가 모르는 시스템도 한 번만 손대면 그 뒤로는 자동이다.
CREATE TABLE field_mapping_memory
(
    id           uuid         NOT NULL,
    tenant_id    uuid         NOT NULL,
    -- 대소문자·공백·밑줄을 지운 형태로 저장한다. BAL_QTY 와 bal qty 는 같은 이름이다.
    source_field varchar(200) NOT NULL,
    target_model varchar(100) NOT NULL,
    target_field varchar(100) NOT NULL,
    -- 몇 번 이렇게 연결했나. 잦을수록 확신이 커진다.
    hit_count    integer      NOT NULL DEFAULT 1,
    last_used_at timestamptz  NOT NULL,
    created_at   timestamptz,
    updated_at   timestamptz,
    CONSTRAINT pk_field_mapping_memory PRIMARY KEY (id)
);

-- 운영 DB 가 PostgreSQL 14 라 NULLS NOT DISTINCT(15+) 를 쓸 수 없다. 네 컬럼이 모두 NOT NULL
-- 이므로 평범한 유니크 인덱스로 충분하다. 갱신은 MERGE(15+) 대신 ON CONFLICT 로 한다.
CREATE UNIQUE INDEX ux_field_mapping_memory
    ON field_mapping_memory (tenant_id, source_field, target_model, target_field);

-- 추천할 때 «이 이름을 전에 어디에 연결했나» 를 찾는다. 자주 쓴 것부터 본다.
CREATE INDEX ix_field_mapping_memory_lookup
    ON field_mapping_memory (tenant_id, source_field, target_model, hit_count DESC);
