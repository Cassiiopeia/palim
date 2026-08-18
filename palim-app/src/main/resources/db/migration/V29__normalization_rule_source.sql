-- 규칙을 어느 원천에 걸지.
--
-- 원천마다 표기 습관이 다르다. 한쪽은 「초콜릿 프로틴바 70g_26.12.12」 처럼 밑줄 뒤에
-- 유통기한을 붙이고, 다른 쪽은 「초콜렛 프로틴바」 처럼 아무것도 붙이지 않는다. 밑줄 규칙을
-- 양쪽에 걸면 지금은 무해해도 밑줄을 쓰는 원천이 하나 더 붙는 순간 조용히 망가진다 —
-- 그때 드러나는 증상은 「이을 만한 것이 줄었다」 뿐이고 원인은 어디에도 안 나온다.
--
-- 비워 두면 모든 원천에 건다. 지금까지 만든 규칙은 전부 이 상태이므로 동작이 바뀌지 않는다.
ALTER TABLE normalization_rule ADD COLUMN source_code varchar(100);

COMMENT ON COLUMN normalization_rule.source_code IS
    '이 규칙을 걸 원천. std_stock_snapshot.source 와 같은 값. NULL 이면 모든 원천';
