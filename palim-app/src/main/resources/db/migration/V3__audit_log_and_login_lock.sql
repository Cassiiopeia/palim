-- ============================================================
-- 감사 로그
-- ============================================================
--
-- 누가 · 언제 · 어디서 · 무엇을 했는지 기록한다.
--
-- actor_id 는 admin_account 를 참조하는 외래키가 아니다. 계정을 지우거나 아이디를 바꿔도
-- "그 시점에 누가 했는지" 가 남아야 하고, 존재하지 않는 계정으로 로그인을 시도한 경우도
-- 기록해야 하는데 FK 로는 저장 자체가 불가능하다.
--
-- updated_at 을 두지 않는다. 감사 로그는 고칠 수 있으면 감사 기능이 아니다.

CREATE TABLE audit_log
(
    id              uuid         NOT NULL,
    occurred_at     timestamptz  NOT NULL,
    actor_id        varchar(50),
    actor_name      varchar(100),
    client_ip       varchar(45),
    audit_type      varchar(40)  NOT NULL,
    target_type     varchar(50),
    target_id       varchar(100),
    summary         varchar(500) NOT NULL,
    before_snapshot text,
    after_snapshot  text,
    request_uri     varchar(300),
    user_agent      varchar(300),
    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);

COMMENT ON TABLE audit_log IS '관리자 감사 로그. 불변 기록이며 UPDATE 하지 않는다';
COMMENT ON COLUMN audit_log.actor_id IS '관리자 아이디. FK 아님 — 계정이 사라져도 남아야 한다';
COMMENT ON COLUMN audit_log.client_ip IS 'IPv6 문자열을 담을 수 있어야 하므로 45자';
COMMENT ON COLUMN audit_log.before_snapshot IS '변경 전 상태(JSON). HTML 을 넣으면 저장형 XSS 창고가 된다';

-- 목록 화면의 기본 정렬이 발생 시각 내림차순이다.
CREATE INDEX ix_audit_log_occurred_at ON audit_log (occurred_at DESC);

-- 조회 조건이 아이디 / 유형 / IP 로 걸린다. 어느 조건이든 기간과 함께 들어오므로
-- 복합 인덱스의 두 번째 컬럼을 occurred_at 으로 둔다.
CREATE INDEX ix_audit_log_actor ON audit_log (actor_id, occurred_at DESC);
CREATE INDEX ix_audit_log_type ON audit_log (audit_type, occurred_at DESC);
CREATE INDEX ix_audit_log_ip ON audit_log (client_ip, occurred_at DESC);

-- ============================================================
-- 로그인 실패 잠금
-- ============================================================
--
-- 실패 횟수를 누적해 계정을 잠근다. 잠금 해제 시각을 저장하는 방식이라 별도 해제 배치가
-- 필요 없다 — 조회 시점에 locked_until 과 현재 시각을 비교하면 된다.

ALTER TABLE admin_account
    ADD COLUMN failed_login_count int NOT NULL DEFAULT 0,
    ADD COLUMN locked_until       timestamptz,
    ADD COLUMN last_login_at      timestamptz,
    ADD COLUMN last_login_ip      varchar(45);

COMMENT ON COLUMN admin_account.failed_login_count IS '연속 로그인 실패 횟수. 성공 시 0 으로 초기화';
COMMENT ON COLUMN admin_account.locked_until IS '잠금 해제 시각. NULL 이면 잠기지 않음';
COMMENT ON COLUMN admin_account.last_login_at IS '최종 로그인 성공 시각. 화면에 표시해 이상 접속을 알아채게 한다';
