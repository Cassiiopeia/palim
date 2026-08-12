-- ============================================================
-- 주간 트렌드 키워드 (#45)
--
-- 외부 서비스를 붙이지 않는다. 우리가 매일 긁는 것 자체가 트렌드 데이터다 —
-- 국내 인기 차트, 검색 결과, 신규 영상 제목이 매일 수천 건 쌓인다. 발굴 대상이 유튜브이므로
-- 유튜브 코퍼스가 오히려 정확한 소스이며, 비용도 리스크도 0이다.
--
-- 집계는 순수 문자열 빈도 계산이라 AI 를 쓰지 않는다.
-- ============================================================

CREATE TABLE trend_keyword
(
    id            uuid         NOT NULL,
    -- 주의 시작일(월요일). 주 단위로 보는 이유는 요일별 업로드 편차를 상쇄하기 위해서다.
    week_start    date         NOT NULL,
    -- 자체 카테고리 코드. 전체 집계는 '_all' 로 저장한다.
    category_code varchar(50)  NOT NULL,
    keyword       varchar(100) NOT NULL,
    frequency     integer      NOT NULL,
    -- 전주 빈도. 증가율을 매번 조인해 계산하지 않도록 집계 시점에 박아 둔다.
    prev_frequency integer     NOT NULL,
    created_at    timestamptz,
    updated_at    timestamptz,
    CONSTRAINT pk_trend_keyword PRIMARY KEY (id)
);

-- 같은 주·카테고리·키워드는 한 행. 재집계는 갱신이다.
CREATE UNIQUE INDEX ux_trend_keyword_unique
    ON trend_keyword (week_start, category_code, keyword);

-- 보드가 "이번 주 카테고리별 상위"를 읽는다.
CREATE INDEX ix_trend_keyword_week_category
    ON trend_keyword (week_start DESC, category_code, frequency DESC);
