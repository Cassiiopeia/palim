-- 알림 재발송 억제용 키
--
-- 왜 필요한가
--   F-05 는 "임계치 미만 상태가 지속될 때 재알림 주기"를 설정 항목으로 규정한다(기본 1일 1회).
--   감시 배치가 매 주기마다 알림을 등록하면 스팸이 되어 발주자가 알림을 아예 보지 않게 되고,
--   그러면 이 시스템의 존재 이유가 무너진다.
--
-- 왜 별도 테이블이 아니라 여기인가
--   Outbox 가 이미 알림 이력이다. 마지막 발송 시각을 다른 곳에 또 저장하면 두 정보가
--   어긋날 여지만 생긴다.
--
-- 키 형식 — {알림종류}:{대상식별자}
--   LOW_STOCK:SKU-001
--   STOCK_MISMATCH:SKU-001
--   DAILY_REPORT:2026-07-29
--
-- NULL 을 허용한다. 신규 주문 알림처럼 억제가 필요 없는 종류는 이 값을 쓰지 않는다.

ALTER TABLE notification_outbox
    ADD COLUMN dedupe_key varchar(200);

COMMENT ON COLUMN notification_outbox.dedupe_key IS '재발송 억제 키. {알림종류}:{대상식별자}. 억제가 필요 없으면 NULL';

-- 최근 발송 여부를 확인하는 조회를 받친다. dedupe_key 가 있는 행만 대상이므로 부분 인덱스로 둔다.
CREATE INDEX ix_notification_outbox_dedupe
    ON notification_outbox (dedupe_key, created_at DESC)
    WHERE dedupe_key IS NOT NULL;
