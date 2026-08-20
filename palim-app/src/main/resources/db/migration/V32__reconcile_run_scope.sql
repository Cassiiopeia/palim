-- 회차가 «자기가 무엇을 견줬는지» 를 남긴다.
--
-- 지금 회차는 좌·우 기준 시각과 건수만 남기고, **어느 창고를 어느 칸으로 더했는지** 를 남기지
-- 않는다. 그래서 지난 회차의 상세를 열면 «오늘의 정의» 로 다시 계산된다.
--
-- 창고를 고르기 전에는 이것이 드러나지 않았다 — 늘 전 창고였으므로 「언제나 같은 답」 이었다.
-- 창고 범위가 생긴 뒤로는 설정을 바꾸는 순간 **지난 회차의 저장된 합계와 화면의 상세가
-- 어긋난다.** 게다가 회차마다 맞기도 하고 틀리기도 해서, 「늘 틀린다」 보다 원인을 찾기 어렵다.
--
-- 비워 두면 「그때는 전 창고를 봤다」 로 읽는다 — 이 마이그레이션 이전 회차가 실제로 그랬다.
ALTER TABLE reconcile_run ADD COLUMN left_warehouses  varchar(1000);
ALTER TABLE reconcile_run ADD COLUMN right_warehouses varchar(1000);
ALTER TABLE reconcile_run ADD COLUMN compare_field    varchar(50);

COMMENT ON COLUMN reconcile_run.left_warehouses IS
    '이 회차가 좌측에서 본 창고. 쉼표 구분. NULL 이면 전 창고';
COMMENT ON COLUMN reconcile_run.right_warehouses IS
    '이 회차가 우측에서 본 창고. 쉼표 구분. NULL 이면 전 창고';
COMMENT ON COLUMN reconcile_run.compare_field IS
    '이 회차가 더한 수치 칸. NULL 이면 기본 칸(base_quantity)';
