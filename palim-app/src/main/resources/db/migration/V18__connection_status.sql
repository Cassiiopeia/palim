-- ============================================================
-- 연결 상태 (#68)
--
-- 등록만 하고 검증하지 않은 것과 검증까지 끝난 것을 구분한다. 이 값이 없으면 화면이
-- "다음에 무엇을 하라"고 말할 수 없고, 사용자는 등록을 마친 뒤 멈춘다.
--
-- 특히 테스트 인증키는 업무 API 를 한 번 성공시키면 소진되는 경우가 있어, 검증에 성공했다고
-- 매일 수집을 돌릴 수 있는 것이 아니다. 키 종류를 함께 기억해 정식 키 교체를 안내한다.
-- ============================================================

ALTER TABLE connector
    ADD COLUMN connection_status varchar(20) NOT NULL DEFAULT 'NOT_CONFIGURED',
    -- TEST / LIVE. 테스트 키로는 매일 수집을 돌릴 수 없다.
    ADD COLUMN credential_kind   varchar(10),
    ADD COLUMN last_verified_at  timestamptz,
    -- 화면을 닫으면 사라지는 오류는 없는 것과 같다. 키 만료처럼 시간이 지나 드러나는
    -- 실패는 남겨두지 않으면 원인을 다시 찾아야 한다.
    ADD COLUMN last_error        varchar(500);

-- 상태판이 "손봐야 할 연결"을 먼저 찾는다.
CREATE INDEX ix_connector_status ON connector (tenant_id, connection_status);
