# 보는 범위 — 두 원천이 같은 모집단이 아닐 때

**상태**: 승인됨 (2026-08-15). 구현은 적재·품목 맞추기가 끝난 뒤.
**이슈**: 미생성 (구현 착수 시 생성)

---

## 문제

두 원천이 **같은 모집단을 담고 있지 않다.**

관측된 모양(일반화): 한쪽 원천은 보관 장소 여러 곳의 재고를 보고하고, 다른 쪽은 그중
한 곳만 보고한다. 지금 대조는 양쪽 총합을 견주므로 **나머지 장소 수량만큼 매일 차이가 난다.**
이 차이는 진짜가 아니라 「비교 대상이 애초에 다른 것」 이다.

같은 모양이 여러 축에서 생긴다 — 한쪽만 불량·보류를 포함한다, 한쪽만 특정 구역을 맡는다.

### 실측 (2026-08-15, 운영)

| 원천 | 행 | 품목 | `warehouse_code` 가짓수 |
|---|---|---|---|
| 전산 쪽 | 45 | 23 | 3 |
| 물류 쪽 | 24 | 24 | **0 (매핑 없음)** |

**오른쪽은 그 축을 아예 보고하지 않는다.** 3PL 은 자기 창고 하나만 다루므로 장소를 줄
이유가 없다. 그래서 「양쪽에서 같은 장소만 고른다」 는 **원리적으로 불가능하다** — 오른쪽엔
고를 값이 없다. 왼쪽 45행이 23품목인 것도 같은 품목이 장소마다 반복되기 때문이다.

설계는 **「이 원천은 이 축을 보고하지 않는다」** 를 다룰 수 있어야 한다.

---

## 핵심 판단: 범위만 연다

손댈 곳은 둘이다 — **어떤 행만 볼지**(범위), **어느 굵기로 묶어 볼지**(해상도).

**해상도는 이미 풀려 있다.** `SnapshotAggregator.sumByUnit` 이 `GROUP BY m.unit_id` 라
장소·로트 분할은 자동으로 접힌다. 「한쪽은 로트로 쪼개 주고 다른 쪽은 합계만 준다」 는
애초에 문제가 아니었다.

**해상도를 여는 것은 위험하다.** `ReconcileEngine.compareUnits` 의 `tolerance` 와
`ReconcileAlertPolicy` 의 `alertThreshold` 가 **차이 한 줄 단위**로 걸린다. 축을 켜서 한
단위의 차이를 칸 수만큼 쪼개면 두 임계의 뜻이 말없이 바뀐다 — 허용오차 5, 세 곳에서 각
4씩이면 켜기 전에는 차이 1건이고 켠 뒤에는 0건이다. 「총합이 보존되니 조용한 실패가 없다」
는 수량 층에서만 참이고 **보고되는 차이 층에서는 거짓이다.**

---

## 설계

### 1. 범위는 「쪽」 에 붙는다 — 원천이 아니라

같은 시스템이 어떤 대조에서는 전산 쪽, 다른 대조에서는 실물 쪽이 된다. 원천에 붙이면
A–B 대조를 맞추려고 A 를 좁힌 순간 **같은 A 를 쓰는 A–C 대조가 조용히 함께 좁아지고**,
A–C 결과 화면 어디에도 왜 좁아졌는지가 안 나온다.

**한쪽에만 조건이 걸리는 것이 이 문제의 정상 모양이다.** 본체가 「한쪽만 여러 곳을
보고한다」 이므로 양쪽 공통 범위 하나로는 표현이 안 된다.

### 2. 축은 자연키 구성 칸뿐 — `warehouse_code` · `lot_code`

취향이 아니라 자료의 사실이다. `std_stock_snapshot` 의 자연키는

```
(tenant_id, source, base_at, item_ref, warehouse_code, lot_code)
```

이고 `StandardModelWriter` 는 `ON CONFLICT ... DO UPDATE` 로 적재한다. 그래서 **자연키 밖의
칸으로 나뉘어 오는 행은 합쳐지지 않고 덮인다.** 원천이 같은 품목·같은 장소·같은 로트를
`quality_status` 로 나눠 두 행(정상 8700, 불량 300)으로 주면 표준 표에는 **마지막 행 하나만
남는다.**

`quality_status`·`zone_code`·`location_code`·`unit`·`currency`·`attributes` 로 범위를 여는
것은 **없는 자료 위에 기능을 얹는 것**이다. 그 축을 열려면 자연키를 넓히는 마이그레이션이
선행돼야 하고, 그것은 별건이다.

`warehouse_name` 도 축이 아니다 — 자연키가 아닌 값 칸이라 매 적재마다 덮인다. 원천이
이름 표기를 바꾸면 조건이 조용히 어긋난다. **라벨로만 쓴다.**

### 3. 스키마

```sql
-- V24__reconcile_scope.sql  (PG14 문법만)

CREATE TABLE reconcile_scope
(
    id            uuid        NOT NULL,
    tenant_id     uuid        NOT NULL,
    definition_id uuid        NOT NULL,
    -- LEFT / RIGHT. 원천 «이름» 이 아니라 «쪽» 이다 — 위 1번 참고.
    side          varchar(10) NOT NULL,
    -- 허용 목록의 축 이름. 자연키 구성 칸만 — 위 2번 참고.
    axis_key      varchar(100) NOT NULL,
    -- INCLUDE(고른 것만) / EXCLUDE(고른 것을 뺀다).
    -- 둘 다 두는 이유는 «새 값이 생겼을 때» 가 정반대이기 때문이다. INCLUDE 는 조용히
    -- 빠지고 EXCLUDE 는 조용히 들어온다. 어느 쪽이 옳은지 코드는 정할 수 없다.
    match_mode    varchar(10) NOT NULL DEFAULT 'INCLUDE',
    -- 그때 «무엇을 보고» 골랐나. 사람이 잘못 골랐을 때 남는 단서.
    decided_base_at timestamptz,
    created_at    timestamptz,
    updated_at    timestamptz,
    CONSTRAINT pk_reconcile_scope PRIMARY KEY (id)
);
-- 같은 (쪽, 축) 에 조건이 둘이면 AND 로 겹쳐 서로를 부정할 수 있다(포함 A + 제외 A →
-- 영영 0행). 컬럼이 전부 NOT NULL 이라 PG14 에서도 평범한 유니크로 충분하다.
CREATE UNIQUE INDEX ux_reconcile_scope_axis
    ON reconcile_scope (tenant_id, definition_id, side, axis_key);

CREATE TABLE reconcile_scope_value
(
    id         uuid         NOT NULL,
    tenant_id  uuid         NOT NULL,
    scope_id   uuid         NOT NULL,
    -- 담긴 값 그대로. 다듬지 않는다(07-DECISIONS 032). 「A창고 」와 「A창고」는 다른
    -- 값이고, 목록에 둘 다 보이는 편이 «자료가 어긋나 있다» 는 사실을 드러낸다.
    -- 빈 문자열도 1급 값이다 — 장소를 안 주는 원천은 warehouse_code 가 '' 다.
    axis_value varchar(255) NOT NULL DEFAULT '',
    -- 고를 때 화면에 함께 보였던 사람 이름. 지어낸 값이 아니라 «그때 자료에 있던 이름»
    -- 이라 짐작이 아니다. 코드만 남기면 반년 뒤 「W01 만 봅니다」 가 어디인지 모른다.
    axis_label varchar(255) NOT NULL DEFAULT '',
    created_at timestamptz,
    updated_at timestamptz,
    CONSTRAINT pk_reconcile_scope_value PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_reconcile_scope_value ON reconcile_scope_value (scope_id, axis_value);

ALTER TABLE reconcile_run
    -- 기계가 견주는 값. 이력 구분선과 승격 판정이 이것«만» 본다. 사람이 읽는 글로
    -- 판정하면 잘린 둘이 우연히 같아 오판한다. '' = 범위 없음 (옛 회차도 사실과 맞는다).
    ADD COLUMN scope_digest       varchar(64)   NOT NULL DEFAULT '',
    -- 그때의 범위를 사람 말로 굳힌다. 결과 화면은 정의가 아니라 회차를 본다 — 안 그러면
    -- 범위를 바꾸는 순간 지난 회차의 설명이 통째로 거짓이 된다.
    ADD COLUMN scope_summary      varchar(1000) NOT NULL DEFAULT '',
    -- 「좌 1240행 중 380행을 봤다」. 기본값을 0 으로 두지 «않는다» — 옛 회차에 0 이
    -- 박히면 「그때 0행이었다」 는 거짓말이 된다. NULL = 「그때는 안 셌다」.
    ADD COLUMN left_row_count     integer,
    ADD COLUMN left_scoped_count  integer,
    ADD COLUMN right_row_count    integer,
    ADD COLUMN right_scoped_count integer;

-- 「지금 안 보고 있는 것」 — 회차마다 값별로.
--
-- 한 줄 요약으로 두면 아무도 안 읽는다. 범위는 본질적으로 «문제를 시야에서 지우는 도구»
-- 라, 제외한 쪽에 «무엇이 얼마나» 남았는지가 표로 남아야 이 기능이 「문제를 숨기는
-- 스위치」 가 되지 않는다.
CREATE TABLE reconcile_run_outside
(
    id         uuid           NOT NULL,
    tenant_id  uuid           NOT NULL,
    run_id     uuid           NOT NULL,
    side       varchar(10)    NOT NULL,
    axis_key   varchar(100)   NOT NULL,
    axis_value varchar(255)   NOT NULL DEFAULT '',
    axis_label varchar(255)   NOT NULL DEFAULT '',
    row_count  integer        NOT NULL,
    quantity   numeric(19, 3) NOT NULL DEFAULT 0,
    created_at timestamptz,
    updated_at timestamptz,
    CONSTRAINT pk_reconcile_run_outside PRIMARY KEY (id)
);
CREATE INDEX ix_reconcile_run_outside_run ON reconcile_run_outside (tenant_id, run_id);
```

`std_stock_snapshot` 은 한 글자도 건드리지 않는다. 담긴 자료는 이미 축을 갖고 있고,
문제는 **조회가 전부 더하는 것**이다.

새 인덱스를 만들지 않는다. 합산·미매칭이 `(tenant_id, base_at DESC, source)` 로 이미
좁혀지고 범위는 그 위의 잔여 필터다. 운영 PG14 는 다른 프로젝트 20여 개와 같은 인스턴스라
인덱스를 늘리는 비용이 우리만의 비용이 아니다 — 하루치가 수십만 행을 넘기 전에는 실측
없이 만들지 않는다.

### 4. 화면 — 묻지 않고 먼저 보여준다

축을 먼저 고르라고 요구하지 않는다. 자기 자료에 무엇이 들었는지 모르는 사람에게 「어느
축이 갈리는지 먼저 짚으라」 고 하면 두세 번 만에 포기한다.

```
보관 장소                                    [ 이 기준으로 범위 정하기 ]
  전산   중앙창고 9000 · 본사창고 1200 · 반품창고 340
  물류   이 기준으로 구분해 주지 않습니다
  → 전산 쪽은 3가지, 물류 쪽은 0가지를 보고합니다.
```

- **수량을 값 옆에 붙이는 것이 이 화면의 핵심이다.** 「중앙창고 9000」 과 「물류 총합 9000」
  이 나란히 보이면 사람이 스스로 안다.
- **코드는 한 칸도 미리 체크하지 않는다.** 담긴 값을 수량과 함께 늘어놓는 것은 짐작이
  아니다 — 판단의 재료를 다 주고 판단은 사람이 한다. 한 줄 판정문은 **사실만** 쓴다
  (「3가지 / 0가지」). 「그래서 이렇게 하세요」 는 쓰지 않는다.
- **구분해 주지 않는 쪽에는 `[범위 정하기]` 가 아예 안 뜬다.** 이 한 줄이 가장 위험한
  실패를 원천 차단한다 — 양쪽에 같은 값을 걸면 한쪽 합계가 0 이 되고, 모든 정합 단위가
  「저쪽이 많음」 으로 잡혀 이튿날 전부 확정 승격 → 알림 폭주.
- **손으로 값을 적는 칸이 화면 어디에도 없다.** 타이핑한 값은 오타 한 글자로 0건이 되고,
  그 오타는 컴파일도 테스트도 DB 제약도 안 잡는다.
- **값이 많은 축도 막다른 길이 아니다.** 수량 많은 순으로 보여주면서 「이 기준에는 값이
  413가지 있고 그중 50가지만 보여드립니다」 라고 정직하게 쓴다.
- **자료가 없을 때**는 빈 상태 카드 + 「한 번 받아온 뒤에 정하세요」 + 받으러 가는 길.

결과 화면에는 **「지금 안 보고 있는 것」** 이 늘 그려진다.

### 5. 안전장치

| 막는 것 | 방법 |
|---|---|
| 구분 안 하는 쪽에 범위를 거는 것 | 화면에 버튼이 안 뜬다 (구조적 차단) |
| 오타로 0건 | 손 입력 칸이 없다 |
| 저장은 됐는데 결과가 0건 | 저장 전 미리보기 + **서버에서도** 「이 범위에 남는 행 0」 을 거부 |
| 원천이 코드를 바꿔 조용히 0건 | 실행 시 계수 — 범위 통과 행이 0이면 회차를 FAILED 로 남긴다 |
| 범위를 바꿔 놓고 그 사실을 잊음 | 회차마다 `scope_digest`·`scope_summary` 를 굳히고 이력 표에 구분선 |
| 범위가 문제를 숨기는 것 | `reconcile_run_outside` 를 결과 화면에 늘 그린다 |

---

## 반드시 함께 고칠 것 (범위 기능과 별개로 이미 깨져 있음)

1. **스케줄러가 실패를 삼킨다.** `ReconcileScheduler.runOne` 이 `!run.isSuccess()` 면
   `log.warn` 후 `return` 한다. 위 가드가 FAILED 를 만들어도 **아무도 모른다** — 조용한
   실패를 막는 장치가 그 자체로 조용하다. 실패 회차도 알림 경로에 올린다.
2. **품목 잇기가 「첫 정의」 를 집는다.** `UnitController.addCandidates` 가
   `findByIsActiveTrueOrderByCode().findFirst()` 로 임의의 정의를 고른다. 정의 이름만 바꿔도
   후보가 달라진다.
   **범위를 후보찾기에 먹이지 않는다** — `reconcile_unit_member` 가
   `(tenant, source, item_ref)` 전역 유니크라 연결은 정의 간 공유다. 범위를 먹이면 **이을
   화면이 없는데 매일 「이으세요」 라고 말하는** 상태가 된다.
3. **알림 payload 계약이 깨져 있다.** `ReconcileNotifier` 가 넣는 키와
   `StockMismatchPayload` 의 컴포넌트가 하나도 안 맞는다.
4. **시험은 초안으로 돌고 적재는 확정판으로 도는데 화면이 그 차이를 말하지 않는다.**
   초안으로 시험에 성공한 뒤 확정을 안 하면 「시험은 됐는데 적재만 실패」 가 된다 —
   실제로 그 상태가 만들어져 있었다.

### 한쪽만 범위 밖이 된 정합 단위

`ReconcileEngine.compareUnits` 가 `getOrDefault(unitId, ZERO)` 를 쓰므로, 좌측 재고가 전부
범위 밖이 된 단위는 **「좌 0 대 우 실수량」 = 전량 차이**로 태어난다. 수량 차이가 아니라
**「범위 밖에 있음」 이라는 별도 유형**으로 내야 한다. 위 가드는 전부 「쪽」 단위라 이것을
못 잡는다.

---

## 이 설계로 못 푸는 것

1. **축 값의 어휘가 양쪽에서 다르면 잇지 못한다.** 왼쪽이 `WH01`, 오른쪽이 `중앙창고` 면
   화면은 두 목록을 나란히 보여줄 뿐이고 「이 둘이 같은 곳」 이라는 대응을 저장하지 않는다.
   축 값 사전(`reconcile_unit` 의 축 버전)이 있어야 제대로 풀리고, 그것은 별건이다.
2. **「어디서 틀렸나」 를 못 답한다.** 결론은 여전히 「이 단위가 451 차이 난다」 이지
   「중앙창고에서만 차이 난다」 가 아니다. 그건 해상도 문제이고, 위에서 열지 않기로 했다.
3. **「한쪽은 불량 포함, 다른 쪽은 가용만」 이 «칸» 으로 오는 경우.** 원천이 그 구분을
   행으로 주면 자연키 문제로 애초에 담기지 않고, 칸으로 주면 이것은 범위가 아니라
   **어느 수치 칸을 볼 것인가** 의 문제다. `compareField` 를 좌·우로 쪼개는 별건이다.

---

## 사람이 정해야 할 것

- **「이 쪽은 구분해 주지 않는다」 를 무엇으로 판정할지.** 「가짓수 1 && 그 값이 빈 문자열」
  로 볼지, 「가짓수 1」 만으로 볼지. 장소가 한 곳뿐인 회사는 값 하나를 보고하는데 그건
  구분을 **하는** 것이다. 앞쪽이 안전하지만, 값이 하나뿐인 원천에 무의미한 조건을 거는
  길은 열어 둔다.
- **값 목록 상한, 선택 개수 상한, 범위 밖 표 행수.** 실제 가짓수를 보고 정한다.
- **범위를 바꾼 날 알림을 끊을지.** 「자를 바꿨으니 새 자로 두 번 재기 전엔 확정이 아니다」
  대 「진짜 차이를 하루 늦게 안다」 — 운영 감각의 문제다.

---

## 이 설계에 이른 경로

설계안 4개를 서로 다른 프레이밍으로 세우고(범위 조건 / 비교 차원 / 원천 담당 범위 /
담긴 값에서 출발), 세 렌즈(일반성·버그여지·사용성)로 심사한 뒤, 종합안을 세 방향에서
깨뜨렸다. 세 반증 모두 `fix-first` 판정이었고, 그 결과 **축 9개 → 2개**, **좌·우 수치 칸
분리 제거**, **가드 6겹 → 3겹**으로 깎였다. 깎인 것들은 대부분 «담기지 않는 자료 위에 얹은
기능» 이었다.
