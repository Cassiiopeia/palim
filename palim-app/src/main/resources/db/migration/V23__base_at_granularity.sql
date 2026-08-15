-- 기준 시각의 눈금.
--
-- 예전에는 「하루」 가 코드에 박혀 있었다. 그래서 한 시간마다 수집하도록 시각을 정해 두면
-- 그날 것이 전부 같은 기준 시각으로 들어가 서로 덮어썼다 — 자연키가
-- (source, base_at, item_ref, warehouse_code, lot_code) 라서다. 오전 10시 재고를 나중에
-- 볼 방법이 없었고, 덮였다는 사실도 어디에도 남지 않았다.
--
-- 원천마다 실제 해상도가 다르다. 이카운트는 기준일을 날짜로만 받고, 물류는 「지금 재고」 를
-- 준다. 그래서 하나로 고정할 수 없고 연동마다 고르게 한다.
--
-- 기본값은 DAY — 지금까지의 동작 그대로다. 이미 담긴 자료의 뜻이 바뀌지 않는다.
ALTER TABLE connector
    ADD COLUMN base_at_granularity varchar(20) NOT NULL DEFAULT 'DAY';

-- 대조에도 같은 칸이 필요하다. 담는 눈금과 견주는 눈금은 다른 값이다.
--
-- 담는 눈금은 「얼마나 촘촘히 남길 것인가」 이고, 견주는 눈금은 「얼마나 굵게 맞춰 볼
-- 것인가」 다. 물류를 한 시간마다 담아도 전산이 하루에 한 번이면 견주기는 하루로 해야
-- 두 원천이 같은 칸에 들어온다. 담는 쪽 값을 그대로 쓰면 이 대조는 영원히 안 돈다.
--
-- 이 값을 여기 두는 또 하나의 이유는 대조가 연동을 알지 않기 때문이다 — 알게 하면
-- 도메인끼리 의존이 생긴다(02-ARCHITECTURE).
ALTER TABLE reconcile_definition
    ADD COLUMN base_at_granularity varchar(20) NOT NULL DEFAULT 'DAY';
