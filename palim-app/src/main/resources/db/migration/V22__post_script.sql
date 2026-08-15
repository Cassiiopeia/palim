-- 담기 전에 값을 다듬는 후처리 스크립트.
--
-- 두 시스템의 품목코드 체계가 완전히 다르다. 이을 실마리는 이름뿐인데, 표기가 조금씩 다르다
-- (「초콜릿」 vs 「초콜렛」, 한쪽에만 붙는 규격·날짜). 규칙 몇 개로 다 풀려고 하면 오히려
-- 위험하다 — 「70g 빼기」 하나로 클래식 다섯 종이 하나로 뭉개진다.
--
-- 어느 규칙이 어디까지 영향을 주는지는 자료를 보면서 사람이 판단해야 한다. 그래서 규칙
-- 편집기를 만들지 않고 스크립트 원문을 그대로 담는다.

CREATE TABLE connector_post_script
(
    id           uuid         NOT NULL,
    tenant_id    uuid         NOT NULL,
    connector_id uuid         NOT NULL,
    -- 사람이 목록에서 알아보는 이름. 「이름 다듬기」 처럼.
    name         varchar(100) NOT NULL,
    -- 파이썬 원문. 화면에서 고친 그대로 담는다.
    body         text         NOT NULL,
    -- 여러 개를 순서대로 돌린다. 앞 결과를 다음이 이어받는다.
    sort_order   integer      NOT NULL DEFAULT 1,
    -- 정의를 바꿔도 과거 실행이 어느 버전으로 돌았는지 남는다(connector_mapping 과 같은 규약).
    version      integer      NOT NULL,
    status       varchar(20)  NOT NULL,
    -- 지우지 않고 꺼 둘 수 있어야 「이게 문제인가」를 하나씩 꺼보며 찾을 수 있다.
    is_enabled   boolean      NOT NULL DEFAULT TRUE,
    timeout_ms   integer      NOT NULL DEFAULT 30000,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,

    CONSTRAINT pk_connector_post_script PRIMARY KEY (id)
);

CREATE INDEX idx_post_script_connector
    ON connector_post_script (connector_id, status, sort_order);

-- 한 연동에서 같은 버전이 둘일 수 없다. 어느 것이 돌았는지 설명할 수 없게 된다.
CREATE UNIQUE INDEX uq_post_script_version
    ON connector_post_script (connector_id, name, version);

-- 언제 어느 버전이 돌았고 몇 건이 바뀌었나.
--
-- 스크립트가 조용히 실패하면 이름만 안 다듬어진 채로 대조가 계속 돈다. 「어제는 69건이
-- 바뀌었는데 오늘 0건」 을 알아채려면 숫자가 남아야 한다.
CREATE TABLE connector_post_script_run
(
    id               uuid        NOT NULL,
    tenant_id        uuid        NOT NULL,
    script_id        uuid        NOT NULL,
    script_version   integer     NOT NULL,
    -- 어느 적재에 딸려 돌았나. 시험 삼아 따로 돌리면 비어 있다.
    connector_run_id uuid,
    status           varchar(20) NOT NULL,
    total_count      integer     NOT NULL DEFAULT 0,
    changed_count    integer     NOT NULL DEFAULT 0,
    -- 스크립트가 stderr 로 남긴 말. 사람이 print() 로 디버깅할 수 있어야 한다.
    stderr_tail      text,
    error_summary    varchar(1000),
    started_at       timestamptz NOT NULL,
    finished_at      timestamptz,

    CONSTRAINT pk_connector_post_script_run PRIMARY KEY (id)
);

CREATE INDEX idx_post_script_run_script
    ON connector_post_script_run (script_id, started_at DESC);
