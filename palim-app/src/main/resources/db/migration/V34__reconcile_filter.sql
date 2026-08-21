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
    operator      varchar(20)  NOT NULL DEFAULT 'EQ',
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
