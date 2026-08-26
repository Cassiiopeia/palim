-- ============================================================
-- 발송 큐가 «어디로 보내는 건인지» 를 스스로 안다 (#173)
--
-- 지금까지 보낼 곳이 하나뿐이라 행에 목적지가 없었다. 두 번째 곳(메일)이 생기면 같은 사건을
-- 두 곳으로 보내야 하는데, 한 행으로 두 곳을 다루면 «한 곳은 갔고 한 곳은 실패한» 상태를
-- 담을 자리가 없다. 그러면 재시도가 이미 간 곳으로 다시 가거나, 못 간 곳을 영영 못 간 채로
-- 둔다.
--
-- 한 사건을 두 곳으로 보낼 때는 «행을 둘» 만든다. 한 행 = 한 상태가 유지되므로 지금의 상태
-- 전이·재발송·이력 화면·재시도 한도가 그대로 산다.
--
-- 기존 행은 전부 TELEGRAM 이 된다 — 이 마이그레이션만으로는 동작이 하나도 바뀌지 않는다.
-- ============================================================

ALTER TABLE notification_outbox
    ADD COLUMN channel varchar(20) NOT NULL DEFAULT 'TELEGRAM';

COMMENT ON COLUMN notification_outbox.channel IS
    '보낼 곳. 한 사건을 여러 곳으로 보낼 때는 행을 나눈다';

-- 중계는 보낼 곳별로 대기분을 읽는다. 기존 (status, created_at) 인덱스만으로는 한쪽이 막혀
-- 쌓이는 동안 다른 쪽 조회까지 그 행들을 매번 훑는다.
CREATE INDEX ix_notification_outbox_channel_status
    ON notification_outbox (channel, status, created_at);
