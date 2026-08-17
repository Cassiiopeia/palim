-- 「이 품목은 짝이 없다」 고 사람이 정해 둔 것.
--
-- 왜 필요한가. 품목 잇기 화면의 할 일 개수가 **영영 0이 되지 않기 때문**이다. 한쪽에만 있는
-- 품목은 언제나 남는다 — 단종됐거나, 이쪽 시스템만 쓰는 부자재이거나, 이번 대조 범위 밖이다.
-- 그것들이 계속 「짝을 못 찾은 것 9건」 으로 떠 있으면 사람은 **다 했는지 아닌지를 알 수 없고**,
-- 결국 그 숫자를 안 보게 된다. 안 보는 숫자는 없는 것과 같다.
--
-- 지우지 않고 표시만 하는 이유는 되돌릴 수 있어야 하기 때문이다. 단종인 줄 알았는데 다시
-- 들어오는 일이 실제로 일어난다.
CREATE TABLE reconcile_item_unpaired
(
    id         uuid         NOT NULL,
    tenant_id  uuid         NOT NULL,
    source     varchar(50)  NOT NULL,
    item_ref   varchar(255) NOT NULL,
    -- 왜 짝이 없는지. 나중에 「단종만 빼고 다시 보기」 같은 것을 하려면 이유가 남아야 한다.
    reason     varchar(30)  NOT NULL,
    note       varchar(500) NOT NULL DEFAULT '',
    created_at timestamptz,
    updated_at timestamptz,
    CONSTRAINT pk_reconcile_item_unpaired PRIMARY KEY (id)
);

-- 같은 품목을 두 번 표시하면 목록에 두 줄로 뜬다. 세 컬럼 모두 NOT NULL 이라
-- PG14 에서도 평범한 유니크로 충분하다.
CREATE UNIQUE INDEX ux_reconcile_unpaired_item
    ON reconcile_item_unpaired (tenant_id, source, item_ref);
