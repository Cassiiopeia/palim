# palim 화면 통일 규칙

`palim-web/src/main/resources/templates` 아래 모든 화면이 따른다. daisyUI 컴포넌트만 쓴다는 전제(07-DECISIONS 009)는 그대로다 — 이 문서는 **어떤 daisyUI 부품을 언제 쓰는가**를 정한다.

**기준선으로 삼을 화면**: `settings/account.html`(색 박스 0, 카드 1, 보조문구 규격 통일), `connector/new.html`(상태색 0, 색 박스 0). 새 화면은 이 둘을 복사해 시작한다.

**어긋난 예의 표기**: `파일:라인` 은 2026-08-14 기준 실측 위치다. 고치면 라인은 밀린다.

---

## 0. 표준값 — 고민하지 말고 이 값을 쓴다

각 값은 임의로 고른 것이 아니라 **현재 코드베이스에서 이미 가장 많이 쓰인 값**이다. 다수를 표준으로 삼아야 고칠 곳이 적다.

| 무엇 | 표준값 | 근거(현재 사용 수) |
|---|---|---|
| 화면 제목 | `layout.html:45` 의 h1 하나. 본문에 h1 금지 | 자체 h1 은 7개 파일뿐 |
| 구역 제목 | `<h2 class="text-base font-semibold">` + `text-xs text-base-content/60` 부연 + `<div class="flex-1 h-px bg-base-300">` | 이 패턴 13곳(5파일)이 최다 |
| 카드 제목 | `card-title text-base` | 40곳 중 25곳 |
| 항목 제목 | `font-medium` | connector/detail 행 라벨 |
| 지표 숫자 | `text-xl font-semibold tabular-nums` | h1(text-2xl)과 겹치지 않게 한 단계 낮춤 |
| 보조문 | `text-sm text-base-content/60` (작으면 `text-xs`) | /60 이 99곳으로 최다, layout:66 이 사용 |
| 죽인 값 | `text-base-content/40` | 값 없음·비활성 전용 |
| 버튼 | `btn btn-sm` | btn-sm 79 vs btn-xs 19 |
| 주 동작 | 화면당 `btn-primary` **1개** | — |
| 뱃지 | `badge badge-sm` | — |
| 카드 | `card bg-base-100 shadow-sm` | shadow 형이 border 형보다 많다 |
| 표 | `table table-sm` | 30곳 vs table-xs 3 / 기본 2 |
| 입력 | `input input-bordered input-sm` (순서까지 이대로) | settings/system 만 순서가 반대 |
| 날짜 | 목록·요약 `MM-dd HH:mm` / 감사·이력 `yyyy-MM-dd HH:mm:ss` | — |
| 값 없음 | `—`(em dash) + `text-base-content/40` | — |

---

## 1. 색

### C1. semantic 색(success/warning/error/info/primary)은 **상태에만** 쓴다. 「눈에 띄게 하려고」는 이유가 아니다
**왜** — 5개 화면군에서 색 사용 195곳을 세어 보면 그중 69곳이 상태가 아니라 강조 목적이었다. 색이 상태와 강조 두 가지를 동시에 뜻하면, 노랑을 보고 「지금 문제가 났다」인지 「앞으로 주의하라」인지 구분할 방법이 사라진다.
**어긋난 예** — `connector/connect.html:113`(badge-warning 「1단계」), `:126`(badge-success 「2단계」), `connector/mapping.html:106`(badge-primary 「자동」), `:131`(text-primary 미리보기 문구), `reconcile/units.html:93`(badge-success 「양쪽에 있음」), `connector/run-detail.html:42`(text-success 「담긴 줄」 숫자)

### C2. 정상 상태에는 색을 쓰지 않는다. 색은 「여기를 보라」일 때만 켠다
**왜** — `connector/list.html` 의 「연결」·「칸 맞추기」 두 열은 어떤 행이든 100% 뱃지가 채워지고 정상 행도 초록 뱃지 두 개다. 연동이 3개면 화면에 뱃지 6~9개가 격자로 깔려, 「다 정상」과 「하나 고장」의 화면 소음이 거의 같아진다. 다 잘 돌아가는 표가 고장난 표만큼 시끄러우면 색은 신호가 아니다.
**어긋난 예** — `connector/list.html:54`(badge-success 「연결됨」), `:59`(badge-success 「맞춤」), `connector/detail.html:25`·`:42`, `fragments/setup-steps.html:11`(끝난 단계에 border-success/40)

→ 대신: **정상은 표시하지 않거나 `text-base-content/60` 평문**, 문제만 `badge-warning`/`badge-error`.

### C3. 한 개념에 색은 한 번만. 같은 카드·행 안에서 반복하지 않는다
**왜** — setup-steps 의 ATTENTION 카드 한 장이 말하는 내용은 「여기 하세요」 하나뿐인데 경고색을 네 번 쓴다.
**어긋난 예** — `fragments/setup-steps.html:10`(테두리) + `:17`(이모지) + `:25`(문구) + `:30`(버튼)

### C4. 컬러 이모지에 `text-*` 색 클래스를 붙이지 않는다
**왜** — ✅(U+2705)·⚠️(U+26A0 FE0F)는 컬러 글리프라 `color` 가 적용되지 않는다. 화면에 아무 영향 없는 죽은 클래스이면서, 읽는 사람에게는 「색이 적용되고 있다」고 오해시킨다.
**어긋난 예** — `fragments/setup-steps.html:16`(text-success on ✅), `:17`(text-warning on ⚠️)

### C5. 색이 말하는 것과 글이 말하는 것을 어긋나게 두지 않는다
**왜** — 경고 테두리 카드 안에 「거의 다 됐습니다 / 정상입니다 / 연결에 문제가 있는 것이 아닙니다」가 들어 있다. 색과 글이 정반대다.
**어긋난 예** — `connector/connect.html:353`(border-warning) ↔ `:355`·`:359` 본문

### C6. 같은 사실은 화면이 달라도 같은 색으로 말한다
**왜** — 실패 건수가 목록에서는 빨강, 상세에서는 노랑이다. 두 화면은 링크로 이어져 있어 사용자가 바로 옆에서 비교하게 된다. 「못 맞춤」 실패는 아예 세 가지 색이다.
**어긋난 예** — `connector/runs.html:64`(text-error) ↔ `connector/run-detail.html:48`(text-warning) / `reconcile/detail.html:55`(badge-warning) ↔ `reconcile/run-detail.html:35`(alert-warning) ↔ `ReconcileController:73` flashError(alert-error)

### C7. 상태 어휘는 4개로 고정한다. 그 밖의 라벨은 전부 `badge-ghost`
| 뜻 | 부품 |
|---|---|
| 실패 | `badge-error` / `alert-error` |
| 확인·조치 필요 | `badge-warning` |
| 방금 일어난 성공 | `badge-success` / `alert-success` |
| 진행 중·현재 단계 | `step-primary` / `btn-primary` |
| 분류·순서·이름 | `badge-ghost` (색 아님) |

**왜** — 지금 badge-success 가 「성공」(connect:298)과 「2단계」(connect:126)와 「양쪽에 있음」(reconcile/units:93)을 동시에 뜻한다. 특히 `reconcile/units.html` 은 파일 주석 7행에 「제안 상태를 확정처럼 보이게 하면 아무도 확인하지 않는다」고 스스로 적어 놓고, 93행의 초록이 바로 그 오독을 만든다.
**어긋난 예** — `connector/connect.html:113`·`:126`, `reconcile/units.html:93`, `connector/runs.html:49`(모드 badge-primary — 모드는 분류다)

### C8. steps rail 은 지나온 단계와 현재 단계를 모두 `step-primary` 로 칠한다
**왜** — 한 rail 안에 초록과 파랑이 섞이면 초록이 「성공」인지 「지나옴」인지 알 수 없다. 두 화면이 같은 rail 을 다르게 칠하고 있다.
**어긋난 예** — `connector/mapping.html:24`(step-success) ↔ `connector/connect.html:29-30`(전부 step-primary)

### C9. 필수 표시(`*`)에 `text-error` 를 쓰지 않는다 — `text-base-content/60` 으로
**왜** — 빨강 별표는 실패가 아니라 라벨인데, 같은 화면군의 `connector/runs.html:60`·`:64` 에서 error 는 실제 실패를 뜻한다. 한 도메인에서 error 의 뜻이 둘로 갈린다.
**어긋난 예** — `connector/mapping.html:100`

---

## 2. 박스 — 한 화면에 몇 개까지

### B1. flash 는 `layout.html:49,52` 가 그린다. 본문에서 다시 그리지 않는다
**왜** — 5개 파일이 같은 `flashSuccess`/`flashError` 를 한 번 더 그려, 상태 변경 후 리다이렉트마다 **같은 초록(또는 빨강) 박스가 세로로 두 개 겹친다.** 여백도 layout 은 `mb-4`, 본문은 `mb-6` 이라 두 박스 간격까지 어긋난다. `reconcile/detail.html` 은 flashError 만 두어 이 화면만 성공은 1번·실패는 2번 렌더된다.
**어긋난 예** — `reconcile/list.html:8,11` · `reconcile/units.html:16,19` · `reconcile/detail.html:8` · `reconcile/run-detail.html:16,19` · `connector/mapping.html:16,19`

### B2. alert 는 「지금 이 화면에서 일어난 일」에만 쓴다. 상시 설명은 alert 가 아니다
**왜** — alert-info 24곳 중 12곳이 상시 설명 박스다. 설명이 flash 와 같은 모양이면 「방금 뭔가 일어났나」로 읽힌다. 특히 `settings/system.html:13` 은 닫을 수도 없어 화면 최상단이 항상 파란 블록이고, 저장 직후에는 layout 의 초록 flash 바로 아래 붙어 색 박스가 2연속이 된다.
**어긋난 예** — `settings/system.html:13` · `connector/units.html:13` · `settings/channels.html:7` · `settings/notification.html:94` · `connector/connect.html:192` · `influencer/rising.html:14` · `influencer/trends.html:13`

### B3. 설명은 색 없는 텍스트로 둔다. 박스가 꼭 필요하면 **색 없는 alert** 를 쓴다
**왜** — 이 앱에 이미 색 없는 중립 alert 패턴이 6곳 있다(`connector/mapping.html:65`, `connector/connect.html:44`·`:373`, `connector/model-detail.html:80`). 같은 성격의 안내를 어떤 화면은 파랑으로, 어떤 화면은 무색으로 그리고 있을 뿐이다 — 무색이 맞다.
**어긋난 예** — `connector/units.html:13`(alert-info) ↔ `connector/mapping.html:65`(무색 alert). 둘 다 「이 화면이 어떻게 도는지」 설명이다

### B4. 사전 주의문에 `alert-warning` 을 쓰지 않는다 — 아직 아무 일도 안 일어났다
**왜** — 「허용 ID 를 확인하세요」·「이름이 비슷해서 묶어 뒀다」는 실패가 아니라 미리 알아둘 것이다. 이것이 경고색이면 같은 화면의 진짜 실패(`connect.html:17`)와 구분되지 않고, 정작 확인해야 할 표보다 노란 박스가 먼저 눈에 들어와 표를 아래로 밀어낸다.
**어긋난 예** — `connector/connect.html:60` · `:216` · `reconcile/units.html:40`

### B5. 첫 스크롤에 보이는 **색 박스는 최대 1개**
**왜** — `connect.html` 은 오류가 있을 때 위에서 아래로 빨강(17) → 회색(36) → 회색(44) → 노랑(60) → 흰 카드(79) → 파랑(192) → 노랑(216) → 회색(231) 순으로 색 박스 8개가 줄지어 선다. 전부 「읽어야 하는 글」이라 어디부터 봐야 할지 우선순위가 없다.
**어긋난 예** — `connector/connect.html` 전체 · `settings/notification.html`(최상단 alert + 파란 stat 숫자 + 카드 안 alert-info)

### B6. 카드는 「독립적으로 다룰 수 있는 덩어리」에만. 설정 한 줄·설명 한 문단은 카드로 감싸지 않는다
**왜** — `settings/system.html` 은 설정 1건 = 카드 1개라 INFLUENCER_SCORING 카테고리에서 **카드가 31개 세로로 반복**된다(ScoringConfigDefinitions 기준). 카드 하나가 담는 것은 라벨+설명+key+입력+범위+버튼 7요소뿐이라 껍데기가 내용보다 크다.
**어긋난 예** — `settings/system.html:31` · `settings/notification.html`(카드 5개가 각각 입력 1~2칸짜리 폼 하나만 담는다)

### B7. 반복 목록은 **카드 반복이 아니라 표 하나**로 그린다
**왜** — 후보가 N개면 테두리 카드 N개가 쌓이고, 각 카드 안에 또 표 1개와 폼 1개가 들어가 박스가 3겹이 된다.
**어긋난 예** — `reconcile/units.html:90`

### B8. 준비·체크리스트 UI 는 끝나면 사라진다
**왜** — `home.html:53` 의 단계 카드 호출이 무조건이고 `SetupService.steps()` 는 항상 정확히 4개를 반환하므로, 4단계가 모두 DONE 인 사용자에게도 색 테두리 카드 4장이 매일 홈 상단을 차지한다. 사라져야 할 체크리스트가 영구 가구가 됐다.
**어긋난 예** — `home.html:53`(조건 없는 fragment 호출)

### B9. 표를 담는 그릇은 하나로 — `card bg-base-100 shadow-sm` + `card-body p-0`
**왜** — 지금 카드형과 카드 없는 `overflow-x-auto` 만 두는 형이 섞여 같은 성격의 표가 두 물건으로 보인다.
**어긋난 예** — `connector/mapping.html:86`·`:159`, `connector/run-detail.html:75`·`:118`, `reconcile/*` 전부(카드 없음) ↔ `connector/runs.html:29-31`, `connector/list.html:32-34`(카드형)

### B10. 카드 표면은 `shadow-sm` 하나로. `border border-base-300` 형과 섞지 않는다
**왜** — shadow 형(`connector/runs.html:20,29`·`connector/units.html:26,60,69`·`connector/new.html:8`·`settings/system.html:31`)과 border 형(`connector/mapping.html:45`·`connector/run-detail.html:150`·`reconcile/units.html:90`·`reconcile/run-detail.html:146`)이 거의 반반이다.
**어긋난 예** — `connector/mapping.html:45` · `connector/run-detail.html:150`

---

## 3. 제목 위계

### T1. 화면 제목은 `layout.html:45` 의 h1 **하나뿐**이다. 본문에 h1 을 만들지 않는다
**왜** — 7개 파일이 자체 h1(`text-2xl font-bold tracking-tight`)을 그리는데 layout 이 이미 같은 값을 `text-2xl font-bold` 로 그린다. `ConnectorController:101` 이 title 을 「{연동명} · 매핑」으로 넣으므로 **「연동A · 매핑」과 「연동A」가 두 줄 연속**으로 나온다. `reconcile/units.html:24`「품목 잇기」와 `UnitController:46` 의 title 은 글자까지 완전히 같다. tracking 만 미세하게 달라 실수로 보이지도 않는다.
**어긋난 예** — `reconcile/list.html:16` · `reconcile/units.html:24` · `reconcile/detail.html:14` · `reconcile/run-detail.html:25` · `connector/mapping.html:32` · `connector/run-detail.html:19` · `connector/detail.html:14`

### T2. 화면 부제는 `layout.html` 이 자리를 준다 — h1 아래 `text-sm text-base-content/70` 한 줄
**왜** — layout 에 부제 자리가 없어서 각 화면이 자체 h1+p 를 만들거나(T1 의 7곳), **alert-info 로 부제를 대신 만들었다**(settings/system:13). 자리를 안 주면 색으로 만들어진다.
**어긋난 예** — `settings/system.html:13` · `connector/units.html:13`(둘 다 실질은 화면 부제다)

### T3. 구역 제목은 하이라인 h2 하나로 고정
```html
<div class="flex items-center gap-3 mb-2">
  <h2 class="text-base font-semibold">구역 이름</h2>
  <span class="text-xs text-base-content/60">부연</span>
  <div class="flex-1 h-px bg-base-300"></div>
</div>
```
**왜** — 이 패턴이 이미 13곳(5파일)으로 최다다. 나머지는 card-title 형(`connector/mapping.html:47`, `connector/units.html:28`)과 제목 자체가 없는 형(`connector/runs.html`, `connector/new.html`, `reconcile/list.html`)으로 갈린다. 같은 레벨의 구획을 세 방식으로 표현한다.
**어긋난 예** — `reconcile/list.html`(페이지 제목 뒤 표가 바로 나온다) · `settings/system-history.html:19`(h2 가 `font-mono text-sm text-base-content/60` — 제목이 보조문구 색이라 표만 덩그러니 뜬다)

### T4. 카드 제목은 `card-title text-base`
**왜** — 40곳 중 text-base 25 / text-lg 7 / 기본 3 으로 갈린다. `fragments/setup-steps.html:22` 는 카드 제목인데 card-title 을 쓰지 않고 `font-semibold` 만 쓴다. `settings/system.html` 은 카드에 제목이 아예 없고 `label.font-medium` 이 제목 역할을 한다.
**어긋난 예** — `connector/list.html:24`(card-title 기본 크기) · `fragments/setup-steps.html:22` · `settings/system.html:39`

### T5. 지표 숫자는 `text-xl font-semibold tabular-nums` — 페이지 제목과 같은 크기를 쓰지 않는다
**왜** — 지금 지표가 `text-2xl` 인데 `layout.html:45` 의 h1 도 `text-2xl` 이다. 「홈」이라는 제목과 「3건」이라는 숫자가 같은 층위로 경쟁해 어디가 제목인지 흐려진다.
**어긋난 예** — `home.html:23,29,34` · `connector/run-detail.html:42,47,53,57` · `reconcile/run-detail.html:45,51,56`

### T6. 한글 라벨에 `uppercase tracking-wider` 를 붙이지 않는다
**왜** — 한글에 uppercase 는 아무 효과가 없다. 영어 대시보드 관용구를 그대로 옮긴 흔적이고 두 파일 7곳뿐이라 나머지 화면과도 어긋난다.
**어긋난 예** — `connector/run-detail.html:41,46,52,56` · `reconcile/run-detail.html:44,50,55`

### T7. `<b>` 는 한 문단에 최대 1개
**왜** — `connect.html` 한 파일에 `<b>` 가 33개다. 224~226행 세 줄 문단 안에 3개, 55행 한 줄에 3개, 368행 한 줄에 3개. 전부 굵으면 어느 것도 강조가 아니다.
**어긋난 예** — `connector/connect.html:55` · `:224-226` · `:368`

---

## 4. 버튼

### N1. 크기는 `btn-sm` 하나. `btn-xs` 와 크기 미지정을 쓰지 않는다
**왜** — btn-sm 79곳 / btn-xs 19곳으로 btn-sm 이 이미 표준인데, 표 행 액션만 xs 로 빠져 **같은 역할의 버튼이 화면마다 눌리는 면적이 다르다.** 크기 미지정은 5곳뿐이라 그 화면만 버튼이 갑자기 커진다.
**어긋난 예** — `connector/list.html:79`(xs) ↔ `connector/detail.html:49`(sm) / 크기 미지정: `connector/list.html:28` · `connector/mapping.html:176` · `settings/account.html:63` · `connector/new.html:67,68`

### N2. 한 화면의 `btn-primary` 는 **1개**
**왜** — `connector/mapping.html` 에 primary 가 3개다(60 「칸 읽기」 · 176 「연결 저장」 · 221 「실제 적재」). 셋 다 파랑이면 무엇이 다음 동작인지 색으로 구분되지 않는다.
**어긋난 예** — `connector/mapping.html:60, 176, 221`

### N3. 위험도가 다른 동작에 같은 색·크기를 쓰지 않는다
**왜** — 「실제 적재」(진짜 자료에 넣는 되돌리기 어려운 동작)와 「칸 읽기」(되돌릴 수 있는 미리보기)가 `btn btn-primary btn-sm` 으로 **완전히 동일**하다. 되돌리기 어려운 동작은 primary 를 쓰되 그 화면의 유일한 primary 여야 하고, 미리보기·조회는 `btn-outline` 또는 무색 `btn`.
**어긋난 예** — `connector/mapping.html:60` ↔ `:221`

### N4. `btn-secondary` 를 쓰지 않는다
**왜** — connector 5개 화면 전체에서 단 한 곳(`mapping.html:212`)에만 나온다. 한 번밖에 나오지 않는 색은 뜻이 학습될 수 없다.
**어긋난 예** — `connector/mapping.html:212`

### N5. 같은 목적지로 가는 버튼은 라벨·색·크기가 같아야 한다
**왜** — `/connectors/connect` 로 가는 버튼이 **라벨 3종·색 2종·크기 2종**이다.
**어긋난 예** — `connector/list.html:17`(「+ 시스템 붙이기」 primary sm) · `:28`(「첫 시스템 붙이기」 primary 기본) · `fragments/setup-steps.html:30`(「하러 가기」 **warning** sm)

### N6. 표 행에서 이름을 링크로 걸었으면 「보기」 버튼 열을 따로 두지 않는다
**왜** — 이름 링크와 「보기」 버튼이 같은 URL 로 간다. 한 행에 같은 목적지가 두 번 있어 표 오른쪽 열 하나를 통째로 낭비한다.
**어긋난 예** — `connector/list.html:49` ↔ `:79`

### N7. 폼 제출 라벨은 「저장」으로 통일하고, 저장 버튼은 전부 같은 강조를 쓴다
**왜** — 동작이 전부 같은 폼 제출인데 라벨이 「저장」/「변경」으로 섞이고, `notification.html` 5개 폼 중 텔레그램 저장(51)만 primary 이고 나머지 4개는 색 없는 btn-sm 이다. 어떤 저장이 더 중요한지 색이 설명하지 못한다.
**어긋난 예** — `settings/notification.html:85,163`(「변경」) · `settings/account.html:63`(「변경」) · `settings/notification.html:51`(혼자 primary)

### N8. 되돌아가기 링크는 `← {목적지}` 형식, `btn btn-ghost btn-sm`
**왜** — 같은 위치의 같은 동작인데 화살표 유무와 어미까지 다르다: 「← 목록」/「목록」/「칸 연결로」/「실행 이력」/「← 설정」.
**어긋난 예** — `connector/mapping.html:38`(화살표 없음) · `connector/run-detail.html:25,27`(「…로」/명사 혼용)

---

## 5. 빈 상태

### E1. 빈 상태는 **카드형 하나**로 고정한다
```html
<div class="card bg-base-100 shadow-sm">
  <div class="card-body items-center text-center py-12">
    <p class="text-base-content/70">아직 …이 없습니다.</p>
    <p class="text-sm text-base-content/60">다음에 할 일 한 줄</p>   <!-- 있을 때만 -->
    <a class="btn btn-primary btn-sm mt-2">…</a>                      <!-- 있을 때만 -->
  </div>
</div>
```
**왜** — 지금 세 가지다. 카드형 6곳(그중 `py-12` 4곳, `py-10` 2곳으로 여백까지 다름), alert 형 15곳, 맨텍스트 형 2곳. 같은 「없음」인데 한쪽은 큰 카드, 한쪽은 회색 한 줄이다.
**어긋난 예** — `connector/runs.html:21`(py-10) · `connector/units.html:61`(py-10) · `reconcile/units.html:152`(맨텍스트) · `reconcile/detail.html:35`(맨텍스트)

### E2. 빈 상태에 alert 를 쓰지 않는다. 특히 `alert-warning` 은 금지
**왜** — 「비어 있음」은 상태가 아니라 사실이다. 지금 alert-info 12곳, alert-warning 3곳이라 **같은 「없음」이 어떤 화면은 파랑, 어떤 화면은 주황**이다. `settings/system.html:26` 은 「표시할 설정이 없습니다」에 경고색을 쓰는데 같은 사실을 `audit/list.html:76` 은 정보색으로 쓴다.
**어긋난 예** — `settings/system.html:26` · `mapping/list.html:27` · `influencer/trends.html:54`(이상 warning) · `audit/list.html:76` · `settings/system-history.html:21` · `monitor/incidents.html:20` · `sku/list.html:49`(이상 info)

### E3. 같은 「없음」을 화면마다 다르게 말하지 않는다. 도메인마다 문구를 하나로 정한다
**왜** — 실행 이력 없음이 「실행 없음」/「아직 실행한 적 없습니다」/「아직 실행 기록이 없습니다.」 세 가지다. 자동 수집 없음은 한쪽이 기호(`—`), 한쪽이 마침표까지 붙은 완전한 문장이다.
**어긋난 예** — `connector/list.html:69` ↔ `connector/detail.html:96` ↔ `connector/runs.html:22` / `connector/list.html:64`(`—`) ↔ `connector/detail.html:60`(「파일로 올리는 방식이라 자동으로 가져오지 않습니다.」)

### E4. 값 없음은 `—` + `text-base-content/40` 하나로. 칸을 비우거나 흐린 0 을 쓰지 않는다
**왜** — 지금 다섯 가지다: em dash, 빈 칸, 흐린 0, `-` 문자, 줄 통째 숨김. 빈 칸은 「값이 없다」인지 「아직 안 그렸다」인지 구분되지 않는다.
**어긋난 예** — `connector/runs.html:68`(elapsedSeconds null 이면 칸이 통째로 빔) · `connector/run-detail.html:48`(흐린 0) · `settings/system.html:42,98`(th:if 로 줄 자체를 감춤) · `AuditLogView.nullToDash`(`-` 문자)

### E5. 목록이 비었다고 섹션이 통째로 사라지게 두지 않는다
**왜** — 「가져온 항목」·「데이터 미리보기」는 비면 섹션 자체가 사라지고 대체 문구가 없다. 「원래 없는 것」인지 「못 가져온 것」인지 알 수 없다. 라벨만 남고 아래가 빈칸이 되는 경우도 같은 문제다.
**어긋난 예** — `connector/connect.html:319` · `:331` · 값 없는 안내문 `:92, :200, :222`

---

## 6. 날짜·숫자

### D1. 화면에 `Instant` 를 그대로 출력하지 않는다
**왜** — 포맷터 없이 `th:text="${...At}"` 을 쓰면 `2026-08-14T03:21:00Z` 같은 ISO-8601 UTC 원문이 사용자에게 그대로 노출된다. 실측 6곳. `reconcile/run-detail.html:27` 은 `font-mono` 까지 씌워 **형식이 있는 것처럼 보이게** 만들어 더 나쁘다.
**어긋난 예** — `reconcile/run-detail.html:27` · `reconcile/detail.html:52` · `connector/runs.html:46` · `audit/list.html:97` · `sku/detail.html:165` · `mock/run.html:75`

### D2. 형식은 두 가지만 — 목록·요약 `MM-dd HH:mm`, 감사·이력 `yyyy-MM-dd HH:mm:ss`
**왜** — 지금 네 종류다: `MM-dd HH:mm`(home:17, connector/list:71, connector/detail:98 — 이 셋은 일치한다), `yyyy-MM-dd HH:mm`(system-history:38), `yyyy-MM-dd HH:mm:ss`(connect:287, AuditLogView.FORMATTER), 포맷 없음(D1). 둘 다 시각·행위자·전후값을 보여주는 같은 성격의 이력 표인데 audit 은 초까지, system-history 는 분까지다.
**어긋난 예** — `settings/system-history.html:38`(분까지) ↔ `audit/list.html:97`(초까지)

### D3. 포맷 위치는 템플릿의 `#temporals.format` 으로 통일한다
**왜** — audit 만 `AuditLogView.FORMATTER` 로 Java 에서 문자열을 만든다. 형식을 바꾸려면 템플릿과 뷰 클래스 두 군데를 찾아야 한다.
**어긋난 예** — `audit/list.html:97`(뷰에서 변환)

### D4. 소요 시간은 사람 단위로 쓴다. 원시 밀리초를 그대로 노출하지 않는다
**왜** — 같은 표 안에서 실행 시각은 사람 형식인데 소요는 가공 없이 `${step.elapsedMs} + ' ms'` 다. 하나는 사람 값, 하나는 기계 값이다.
**어긋난 예** — `connector/connect.html:313-314`

### D5. enum 을 화면에 원문으로 노출하지 않는다
**왜** — `connector/runs.html:58` 은 `SUCCEEDED`/`PARTIAL`/`ROLLED_BACK` 을 영문 대문자로 그대로 찍는데, **바로 왼쪽 칸**(`:50`)의 모드 badge 는 「테스트」/「적재」로 한글이다. 한 표 안 같은 성격의 두 badge 가 다른 언어다. 설정 탭도 `CATEGORY_NAMES` 에 4개만 등록돼 있어 「인플루언서 점수 기준」과 「INFLUENCER_TREND」가 나란히 붙는다.
**어긋난 예** — `connector/runs.html:58` · `settings/system.html:20-23`(`SystemConfigController:38-42, 57-61`)

### D6. 표 안 수치는 `font-mono text-xs tabular-nums`, 지표 숫자에도 `tabular-nums` 를 붙인다
**왜** — 표는 이미 지키는데 KPI 숫자만 font-mono 가 빠져 자릿수 정렬 기준이 달라진다.
**어긋난 예** — `reconcile/run-detail.html:45,51,56`

---

## 7. 설명문

### X1. 보조 텍스트 농도는 두 단계만 — 기본 `/60`, 죽인 값 `/40`
**왜** — 지금 여섯 단계다: **/60 99곳, /70 51곳, /50 18곳, /40 9곳, /80 3곳, /30 1곳.** 같은 「부가 설명」 역할에 세 단계가 섞여 어떤 값이 어떤 역할인지 대응이 없다 — 규칙이 아니라 그때그때 고른 값으로 보인다. `layout.html:66` 이 /60 을 쓰므로 /60 이 사실상의 기준값이다.
**어긋난 예** — `settings/system.html:44`(/30 — 대비가 사실상 판독 불가) · `connector/connect.html:53,71,223`(/80) · `reconcile/units.html:99`·`reconcile/list.html:26`(/50) · `connector/mapping.html:130`(/70) ↔ `connector/run-detail.html:132`(/50, 같은 등급 텍스트)

### X2. 임의 크기값(`text-[10px]`)을 쓰지 않는다. Tailwind 스케일(`text-xs`)만
**왜** — 4곳뿐인 예외이고, 그중 `settings/system.html:44` 는 10px + 30% 불투명도라 읽을 수 없다.
**어긋난 예** — `settings/system.html:44,85,99` · `influencer/grades.html:119`

### X3. 필드 도움말은 `label-text-alt` 하나로. 필드 옆 설명을 alert 나 별도 `<p>` 로 만들지 않는다
**왜** — 같은 성격의 설명이 네 가지 그릇에 담긴다: `label-text-alt`, 별도 `<p class="text-xs text-base-content/60">`, 무색 alert, 색 alert. 어떤 안내가 박스 밖이고 어떤 것이 박스 안인지에 규칙이 없다.
**어긋난 예** — `connector/connect.html:192`(alert-info) · `connector/mapping.html:170,224`(별도 p) · `connector/units.html:53`(별도 p) ↔ `connector/new.html:16,25,38,50,60`(label-text-alt, 이쪽이 맞다)

### X4. 같은 사실을 한 화면에서 두 번 말하지 않는다
**왜** — 인증키 입력칸 하나에 도움말이 3줄 쌓이는데 `:192` 와 `:203` 은 사실상 같은 문장이다. 「테스트 인증키는 1회용」은 세 곳, 발급 경로는 두 곳에 있고 **반복할 때마다 다른 색 컨테이너를 써서 세 번 다 새 정보처럼 보인다.**
**어긋난 예** — `connector/connect.html:192` ↔ `:203` / `:44-51` ↔ `:114-118` ↔ `:358-360` / `:55` ↔ `:368`

### X5. 「지금 하는 데 필요한 것」만 펼치고 「막혔을 때 필요한 것」은 collapse 로 접는다. 접기 안에 또 박스를 넣지 않는다
**왜** — 발급 안내 collapse 는 `report == null` 이면 기본으로 열려 있어 **첫 화면부터 노출**되고, 그 회색 박스 안에 또 중립 alert → 노랑 alert 가 들어가 박스가 2겹이다.
**어긋난 예** — `connector/connect.html:36`(th:open) · `:44` · `:60`

### X6. alert 안 글자 크기는 `text-sm` 하나로. 패딩 수정자를 붙였다 뺐다 하지 않는다
**왜** — `text-sm`(18,193,219)과 `text-xs`(45,61,374)가 섞여 같은 alert 인데 어떤 것은 본문 크기, 어떤 것은 각주 크기로 읽힌다. `py-2` 도 44·60·192·373 에는 있고 17·216 에는 없어 나란히 놓이면 세로 여백이 서로 다르다.
**어긋난 예** — `connector/connect.html:45,61,374` · `:17,216`

### X7. 색 범례를 화면 안에 적지 않는다
**왜** — 「지금 손봐야 할 곳은 색으로 표시됩니다」는 `today.ran == false` 일 때만 나오므로(`home.html:46`), **매일 오는 사용자에게는 설명 없는 색만 남는다.** 설명을 적어야 이해되는 색 체계는 색 규칙을 고쳐야 한다는 뜻이지 문장을 추가할 일이 아니다.
**어긋난 예** — `home.html:49`

---

## 8. 폼·표

### F1. 입력은 `input input-bordered input-sm` — 클래스 순서까지 이대로
**왜** — 크기가 한 폼 안에서 갈린다(접속 정보 칸은 기본, 접속 주소 칸만 sm). 화면 간에도 `connector/new.html` 은 기본, `connector/mapping.html`·`connector/units.html` 은 sm 이라 같은 폼 요소가 화면마다 다른 높이다. 순서는 `settings/system.html` 만 `input input-sm input-bordered` 로 반대라 grep 이 안 걸린다.
**어긋난 예** — `connector/connect.html:243,247,256`(sm) ↔ `:144,166,180`(기본) · `settings/system.html:55,62,67,76,81`(순서)

### F2. 폼 라벨은 `<div class="label"><span class="label-text font-medium">` 하나로
**왜** — 세 갈래다: 기본 패딩+기본 크기(`connector/new.html` 전 필드), `label py-1`+text-xs(`connector/units.html:32,37,42,47`), `label py-0`+text-xs(`connector/mapping.html:56`, `reconcile/units.html:124,130`). 여기에 label 을 아예 안 쓰는 곳과 span 으로 폭만 고정한 유사 라벨까지 있다.
**어긋난 예** — `settings/system.html:39`(label.font-medium 만) · `audit/list.html:18,37,52`(span.w-14)

### F3. 표는 `table table-sm`. 나란히 놓인 표의 크기를 다르게 하지 않는다
**왜** — table-sm 30곳으로 이미 표준인데 기본 2곳·table-xs 3곳이 남아 있다. `connect.html` 은 **한 카드 안에서** 검증 결과(290 sm)와 미리보기(333 xs)가 한 단계 차이 나 글자 크기가 갑자기 작아진다.
**어긋난 예** — `connector/connect.html:333` · `reconcile/units.html:104` · `connector/list.html:35` · `reconcile/list.html:34`

### F4. `table-zebra` 는 이력성 표에 전부 붙이거나 전부 뺀다
**왜** — `audit/list.html:81` 은 zebra 가 있고 `settings/system-history.html:26` 은 없다. 둘 다 시각·행위자·전후값을 보여주는 같은 성격의 표인데 다른 물건으로 보인다.
**어긋난 예** — `settings/system-history.html:26`

### F5. 표 헤더 행에 `text-xs` 를 붙일지 말지 하나로 정한다(붙이는 쪽이 다수)
**왜** — `<tr class="text-xs">`(connector/mapping:89, connector/run-detail:78,121, reconcile 전부)와 기본(connector/runs:34, connector/units:74)이 섞인다.
**어긋난 예** — `connector/runs.html:34` · `connector/units.html:74`

### F6. `layout.html` 이 본문 최대 폭을 정한다. 화면이 각자 max-width 를 들지 않는다
**왜** — `layout.html:43` 의 main 에 max-width 컨테이너가 없어 `settings/account.html` 만 스스로 `max-w-lg` 를 걸었고 나머지는 전폭이다. 와이드 모니터에서 폼 한 줄이 화면 끝까지 늘어난다.
**어긋난 예** — `settings/account.html:13,22`

---

## 9. 화면 사이 일관성

### S1. 상태를 말하는 부품은 badge 하나. 이모지+테두리색과 섞지 않는다
**왜** — 같은 「됐다/손봐야 한다」를 setup-steps 는 이모지+테두리색(✅⚠️⬜ + border-warning/border-success)으로, connector/list·detail 은 daisyUI 뱃지로 말한다. 홈에서 이모지를 보고 목록으로 넘어가면 **같은 개념을 완전히 다른 부품으로 다시 배워야 한다.**
**어긋난 예** — `fragments/setup-steps.html:10-11, 16-18`

### S2. badge 는 상태 전용. 순서 라벨·아이콘·칩으로 쓰지 않는다
**왜** — `connect.html` 한 화면에서 badge 가 네 가지를 뜻한다: 상태(282,283,298,299) / 순서 라벨(113,126) / 툴팁 물음표 아이콘(103,162) / 필드명 칩(322). 같은 모양이 네 가지를 뜻하면 어느 것도 신호가 되지 못한다. 물음표 아이콘은 `text-base-content/60` 텍스트로, 칩은 `badge-ghost` 로.
**어긋난 예** — `connector/connect.html:103,113,126,162,322`

### S3. badge 크기는 `badge-sm` 으로 고정
**왜** — 같은 「통과/성공」인데 결과 요약은 기본 크기(282,283), 표 안 단계 결과는 badge-sm(298,299), 필드 칩은 크기 지정 없음(322)이다.
**어긋난 예** — `connector/connect.html:282,283,322` · `connector/detail.html:16` · `audit/list.html:73`

### S4. 목록 표의 열과 상세 화면의 행은 **이름과 구성이 이어져야** 한다
**왜** — 목록 열은 연결·칸 맞추기·자동 수집·마지막이고 상세 행은 연결·칸 맞추기·자동 수집·단위 환산·실행 이력이다. 목록에서 본 「마지막」이 상세의 어느 행인지 이름으로는 이어지지 않는다. 게다가 상세 5행 중 2행만 상태 뱃지를 갖고, 오른쪽 끝 요소도 버튼/안내 문구/아무것도 없음으로 행마다 다르다 — 같은 리스트로 보이지만 행마다 다른 물건이다.
**어긋난 예** — `connector/list.html:38-43` ↔ `connector/detail.html:23,40,56,86,94`

### S5. 목록에서 붉게 표시한 것은 상세에서도 같은 세기로 표시한다
**왜** — 목록은 수집 실패를 `badge badge-error` 로 외치는데, 상세는 같은 실패를 「성공 N건, 실패 N건」 평문으로 쓰고 실패>0 에도 색이 없다. 목록에서 빨강을 보고 들어가면 아무 표시가 없어 잘못 들어온 줄 안다.
**어긋난 예** — `connector/detail.html:99`

### S6. 같은 값을 한쪽은 뱃지, 한쪽은 평문으로 그리지 않는다
**왜** — `sourceType` 이 목록에서는 `text-xs text-base-content/60` 평문, 상세에서는 `badge badge-ghost` 다. 분류값이므로 평문 쪽으로 통일한다.
**어긋난 예** — `connector/detail.html:16`

### S7. 흐름 화면에는 steps rail 을 끝까지 유지한다
**왜** — connect·mapping 에는 rail 이 있는데 runs·run-detail 로 넘어가면 통째로 사라져 사용자가 흐름 중 어디인지 알 수 없다. 여백도 `mb-8`(mapping) vs `mb-6`(connect) 로 다르다 — `mb-6` 으로 고정.
**어긋난 예** — `connector/runs.html` · `connector/run-detail.html`(rail 없음) · `connector/mapping.html:23`(mb-8)

### S8. 같은 단어를 한 화면에서 두 축으로 쓰지 않는다
**왜** — 상단 steps 의 「1단계」는 인증키 준비인데 라디오 badge 의 「1단계」는 테스트 인증키다. 같은 화면에서 같은 숫자가 다른 것을 가리킨다.
**어긋난 예** — `connector/connect.html:29` ↔ `:113`

### S9. 같은 사실을 두 화면이 다른 차림새로 말하지 않는다
**왜** — 「아직 연결한 시스템이 없습니다」라는 **똑같은 문구**를 한쪽은 카드+card-title+설명+CTA 를 `items-center text-center py-12` 로 크게 차리고, 다른 쪽은 단계 카드 한 줄 안의 `text-sm` 설명으로 처리한다.
**어긋난 예** — `connector/list.html:22-30` ↔ `fragments/setup-steps.html`(step.detail)

### S10. 테두리로 상태를 말할 때 한쪽만 투명도를 다르게 하지 않는다
**왜** — `border-warning` 은 불투명인데 `border-success/40` 만 40% 라 같은 규칙 안에서 두 상태의 테두리 무게가 다르다. (C2 를 적용하면 success 테두리 자체가 사라지므로 이 문제도 함께 없어진다.)
**어긋난 예** — `fragments/setup-steps.html:10-11`

---

## 10. PR 체크리스트

화면을 추가하거나 고칠 때 이 열 줄만 확인한다.

1. **h1 을 본문에 넣지 않았는가** (T1) — 제목은 layout 이 그린다
2. **flash 를 본문에서 다시 그리지 않았는가** (B1)
3. **첫 스크롤의 색 박스가 1개 이하인가** (B5)
4. **켜 놓은 semantic 색이 전부 「지금 상태」인가** (C1) — 순서·분류·강조에 색이 없는가
5. **정상 상태에 색이 없는가** (C2)
6. **btn-primary 가 1개인가, 나머지 버튼이 전부 btn-sm 인가** (N1·N2)
7. **빈 상태가 카드형인가, alert 가 아닌가** (E1·E2)
8. **시각이 `#temporals.format` 을 거쳤는가** (D1·D2)
9. **보조문 농도가 /60 또는 /40 인가** (X1)
10. **같은 사실을 이 화면에서 두 번 말하지 않는가** (X4)

---

## 11. 적용 순서 (영향 대비 비용)

| 순서 | 작업 | 대상 |
|---|---|---|
| 1 | 본문 h1 7곳 삭제 | T1 — 라인 삭제만, 위험 0 |
| 2 | 본문 flash 5파일 삭제 | B1 — 라인 삭제만, 위험 0 |
| 3 | 죽은 색 클래스 2곳 삭제 | C4 — 렌더 결과 변화 없음 |
| 4 | 포맷 없는 시각 6곳에 포맷터 적용 | D1 — 사용자가 바로 체감 |
| 5 | 빈 상태 alert 15곳 → 카드형 | E1·E2 |
| 6 | 정상 상태 badge-success 제거 | C2 — 목록 화면 소음이 가장 크게 줄어든다 |
| 7 | 강조용 색 제거(badge-warning「1단계」 등) | C1·C7 |
| 8 | 상시 설명 alert-info 12곳 → 무색 | B2·B3 |
| 9 | 버튼 크기·강조 정리 | N1~N5 |
| 10 | layout 에 부제 자리·max-width 추가 | T2·F6 — 이후 화면이 각자 발명하지 않게 하는 예방책 |

**참고**: 날짜 형식은 `home.html:17`·`connector/list.html:71`·`connector/detail.html:98` 세 화면이 이미 `MM-dd HH:mm` 으로 일치한다 — 이 셋은 손대지 않는다. `settings/account.html`·`connector/new.html` 도 그대로 둔다.
---

## 화면에 코드를 박지 않는다

스크립트·스타일은 **자기 도메인 파일로만** 제한되어 있다(07-DECISIONS 009·031). 태그 안에 직접
적은 것은 브라우저가 실행하지 않는다.

| 쓰면 안 되는 것 | 대신 |
|---|---|
| `<script> … </script>` (본문에 직접) | `/js` 아래 파일 + `layout.html` 에서 불러오기 |
| `onchange="this.form.submit()"` | `data-auto-submit` 표시 + `auto-submit.js` |
| `style="display:none"` · `th:style` | `th:if` 로 서버가 그리거나, 클래스(`hidden`) |

**골라서 화면이 바뀌어야 하면 서버가 다시 그린다.** 값을 주소로 받아(`?preset=…`) 서버가 전부
그린다. 절반은 서버가, 절반은 스크립트가 고치는 구조는 스크립트가 막히는 순간 어긋난 채
**멀쩡해 보인다.**

**스크립트에 기대는 동작에는 대체 길을 함께 둔다.** 스크립트가 살아 있으면 숨고
(`data-auto-submit-fallback`), 막히면 남아서 길이 된다. 고를 수는 있는데 반영할 방법이 없는
상태를 만들지 않는다.

렌더 테스트(`RenderAssertions.noInlineCode()`)가 모든 화면에서 이것을 검사한다.
