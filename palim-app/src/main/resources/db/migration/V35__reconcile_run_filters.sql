-- 회차가 «그때 무엇을 봤는지» 를 조건까지 남긴다.
--
-- V32 가 창고 CSV 로 하던 일을 넓힌다. 조건이 창고 하나에서 여러 칸으로 늘었으므로 CSV 두
-- 칸으로는 담을 수 없다.
--
-- **글로 남긴다.** 조건 나무를 구조화해 담지 않고 사람이 읽는 식으로 적는다.
--   · 그 글이 곧 «다시 계산할 수 있는» 기록이다 — 파서가 되읽으면 그때의 조건이 되살아난다
--   · 카탈로그에서 사라진 칸도 그대로 남는다. 구조화하면 없는 칸을 가리키는 기록이 되어 터진다
--   · 푼 날짜를 따로 담지 않는다. 회차는 자기가 돈 시각(started_at)을 이미 알므로 「오늘+30」 을
--     그 시각으로 다시 풀면 똑같은 답이 나온다. 파생값을 저장하면 어긋날 자리만 생긴다
--
-- 비어 있으면 V32 의 옛 창고 칸을 읽는다. 그 이전 회차는 「전 창고를 봤다」 로 읽는다.
ALTER TABLE reconcile_run ADD COLUMN filters_left  varchar(2000);
ALTER TABLE reconcile_run ADD COLUMN filters_right varchar(2000);

COMMENT ON COLUMN reconcile_run.filters_left IS
    '이 회차가 좌측에서 본 조건. 사람이 읽는 식이자 되읽을 수 있는 기록. NULL 이면 left_warehouses 를 읽는다';
COMMENT ON COLUMN reconcile_run.filters_right IS
    '이 회차가 우측에서 본 조건. NULL 이면 right_warehouses 를 읽는다';
