-- 한 연동이 **두 가지 길**로 자료를 받을 수 있게 한다.
--
-- 공개 API 가 없는 원천은 상대 화면이 쓰는 경로를 그대로 흉내 내서 가져온다. 그런데 **상대
-- 사이트가 바뀌면 그날로 깨진다** — 로그인 방식, 조회 주소, 응답 모양 어느 하나만 달라져도
-- 자동 수집이 멈춘다. 그때 사람이 파일을 받아 올려 계속 돌릴 수 있어야 업무가 안 멈춘다.
--
-- **파일은 「같은 원천 이름」 으로 들어가야 한다.** 별도 연동을 새로 만들면 원천 이름이
-- 달라져서 그동안 묶어 둔 품목이 통째로 무용지물이 된다 — 급할 때 쓰는 길인데 그때 묶기부터
-- 다시 하라는 셈이다.
--
-- 그런데 **엑셀 열 이름은 API 칸 이름과 다르다**(API 는 stock_qty, 엑셀은 「재고수량」).
-- 그래서 칸 맞추기를 길마다 따로 둔다. 기존 것은 전부 자동 수집용이다.
ALTER TABLE connector_mapping ADD COLUMN intake varchar(20) NOT NULL DEFAULT 'AUTO';

-- 확정판은 **길마다 하나씩**이다. 예전 제약이 연동당 하나였다면 파일용을 확정하는 순간
-- 자동 수집용이 밀려난다.
DROP INDEX IF EXISTS ux_connector_mapping_active;
CREATE UNIQUE INDEX ux_connector_mapping_active
    ON connector_mapping (connector_id, intake) WHERE status = 'ACTIVE';

-- 파일을 어디서 어떻게 받는지. 상대 사이트가 바뀌면 사람이 그 자리에서 고쳐 둘 수 있어야
-- 다음부터 그게 정답이 된다.
ALTER TABLE connector ADD COLUMN file_guide varchar(2000) NOT NULL DEFAULT '';
