-- 어느 창고끼리 견줄지.
--
-- 재고를 맡긴 곳은 자기가 보관 중인 것만 안다. 그런데 전산 쪽에는 창고가 여럿이다 — 위탁 창고,
-- 사무실, 매장. **전부 더해서 견주면 위탁하지 않은 물량만큼 무조건 어긋난다.**
--
-- 실측(2026-08-19): 전 창고를 더해 견주면 총합 차이가 754개로 나오는데, 위탁 창고만 견주면
-- 1개다. 그것뿐이면 「숫자가 좀 크다」 로 끝나지만, 맞던 품목까지 틀린 것으로 보인다 —
-- 일치가 11건에서 3건으로 무너진다. 대조가 사실상 쓸모없어진다.
--
-- **비워 두면 전부 본다.** 지금까지의 동작이 그것이고, 이미 만들어 둔 정의가 이 값 없이 있다.
-- 대신 양쪽 창고 수가 어긋나면 화면이 짝을 정하라고 안내한다 — 조용히 틀린 답을 내는 것보다 낫다.
--
-- 쉼표로 구분한다. 창고 짝은 개별 대응이 아니라 «집합 대 집합» 이라 별도 표가 조인만 늘린다.
-- 나중에 창고별로 결과를 쪼갤 일이 생기면 그때 표로 옮긴다.
ALTER TABLE reconcile_definition ADD COLUMN left_warehouses  varchar(1000);
ALTER TABLE reconcile_definition ADD COLUMN right_warehouses varchar(1000);

COMMENT ON COLUMN reconcile_definition.left_warehouses IS
    '좌측 원천에서 볼 창고 코드. 쉼표 구분. NULL 이면 전체';
COMMENT ON COLUMN reconcile_definition.right_warehouses IS
    '우측 원천에서 볼 창고 코드. 쉼표 구분. NULL 이면 전체';
