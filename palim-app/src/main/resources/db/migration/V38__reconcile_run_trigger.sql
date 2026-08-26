-- ============================================================
-- 사람이 누른 것과 저절로 돈 것을 가른다 (#173)
--
-- 실행 시각을 화면에서 바꿀 수 있게 하면서 스케줄러가 «짧게 자주 깨어나 시각이 지났는지
-- 보는» 방식으로 바뀐다. 그러면 「오늘 이미 돌았나」 를 실행 이력으로 판단할 수밖에 없는데,
-- 그 이력에는 사람이 누른 「지금 맞춰 보기」 가 섞여 있다.
--
-- 구분이 없으면 «사람이 아침에 한 번 눌렀다는 이유로 그날 자동 대조가 통째로 건너뛰어진다.»
--
-- 기존 행을 MANUAL 로 두는 이유 — 과거 회차가 자동이었는지 알 방법이 없다. MANUAL 로 두면
-- 「오늘 자동분 없음」 으로 읽혀 최악의 경우 한 번 더 도는 데 그치지만, SCHEDULED 로 두면
-- 반대로 그날 대조를 건너뛴다.
-- ============================================================

ALTER TABLE reconcile_run
    ADD COLUMN trigger_type varchar(20) NOT NULL DEFAULT 'MANUAL';

COMMENT ON COLUMN reconcile_run.trigger_type IS
    '무엇이 이 회차를 시작했나. MANUAL(사람) · SCHEDULED(정해진 시각)';

CREATE INDEX ix_reconcile_run_trigger
    ON reconcile_run (definition_id, trigger_type, started_at DESC);
