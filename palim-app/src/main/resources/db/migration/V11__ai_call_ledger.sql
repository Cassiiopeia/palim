-- ============================================================
-- AI 호출 원장 (#41)
--
-- OpenAI 는 실제로 과금된다. 버튼 연타·재시도 루프·다른 서비스와의 키 공유 때문에
-- 사용량이 예상을 넘는 것이 흔한 사고이며, 그때 남는 것은 청구서뿐이다.
--
-- 쿨다운(연타 방지)은 메모리 캐시로 충분하지만, 일일 상한은 여기 남긴다 —
-- 프로세스가 재시작되면 메모리 카운터는 0 이 되고 상한이 무의미해지기 때문이다.
-- ============================================================

CREATE TABLE ai_call_ledger
(
    id         uuid    NOT NULL,
    usage_date date    NOT NULL,
    call_count integer NOT NULL,
    created_at timestamptz,
    updated_at timestamptz,
    CONSTRAINT pk_ai_call_ledger PRIMARY KEY (id)
);

CREATE UNIQUE INDEX ux_ai_call_ledger_date ON ai_call_ledger (usage_date);
