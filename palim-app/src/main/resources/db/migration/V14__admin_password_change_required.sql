-- ============================================================
-- 초기 비밀번호 변경 강제 (#51)
--
-- 환경변수 없이 기동하면 기본 계정(admin/admin)이 만들어진다. 이 저장소는 PUBLIC 이고 화면은
-- 인터넷에 노출되므로, 기본값은 비밀이 아니라 공개된 값이다 — 그대로 두면 배포 즉시 누구나
-- 들어올 수 있다.
--
-- 그래서 기본값으로 만든 계정은 이 플래그를 세우고, 변경 전까지 다른 화면을 쓰지 못하게 한다.
-- 공유기·NAS 가 쓰는 방식이며 기동 편의와 안전을 동시에 만족하는 유일한 조합이다.
-- ============================================================

ALTER TABLE admin_account
    ADD COLUMN password_change_required boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN admin_account.password_change_required
    IS '초기 비밀번호 사용 중. true 면 비밀번호 변경 외 모든 화면이 차단된다 (#51)';

-- 기존 계정은 발주자가 정한 비밀번호를 쓰고 있으므로 강제하지 않는다.
-- (DEFAULT false 로 이미 채워져 있다. 명시적으로 남겨 의도를 드러낸다.)
UPDATE admin_account SET password_change_required = false;
