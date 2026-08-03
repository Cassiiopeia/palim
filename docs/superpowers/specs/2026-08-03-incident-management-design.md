# 인시던트 관리 설계 (#35)

로드맵 순서 4. 오버셀·재고 정합성 불일치·미매핑 상품을 **미확인 → 확인 → 해결** 상태로
관리한다. 텔레그램 알림은 도달하면 끝이라 "확인했는지, 조치했는지"가 어디에도 남지 않는다 —
인시던트는 그 공백을 메우는 기록이다.

## 확정 요구사항

| 항목 | 결정 |
|---|---|
| 대상 | 로드맵 3종 — OVERSELL · STOCK_MISMATCH · UNMAPPED_PRODUCT |
| 재발 처리 | 미해결 건에 발생 횟수·최근 발생 시각 누적. 해결 후 재발만 새 인시던트 |
| 해결 방식 | 수동 해결만. 자동 해결(재검산 통과·매핑 등록)은 감시 배치와 결합이 생겨 제외 |

수집 실패는 제외한다 — 수집 모니터(#30)가 이미 상태를 보여준다. 인시던트 발생 자체의 알림도
만들지 않는다 — 원본 이벤트가 이미 텔레그램으로 나가므로 이중 알림이 된다.

## 모듈 배치 — 새 도메인 모듈 `palim-incident`

인시던트는 collector(오버셀·미매핑)와 monitor(정합성 불일치) **양쪽에서 생성**된다.

- 조율 계층은 서로 의존하지 않으므로(02-ARCHITECTURE) 어느 한쪽이 소유할 수 없다
- `palim-notification` 내부에 두는 안은 기각 — 알림은 발송하면 끝(Outbox 상태 기계),
  인시던트는 사람이 마감하는 장부(상태 관리)로 생명주기가 다르다

`palim-sku` 와 같은 형태의 도메인 모듈로 만든다: `java-library` + `api(palim-common)`.
컴포넌트 스캔은 패키지 기반(`kr.suhsaechan.palim`)이라 별도 설정이 필요 없다.

## 엔티티 `Incident` — Flyway `V4__incident.sql`

| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | uuid | UUIDv7, 애플리케이션 생성 |
| type | varchar(30) | `IncidentType` — OVERSELL · STOCK_MISMATCH · UNMAPPED_PRODUCT |
| dedupe_key | varchar(200) | `{유형}:{대상식별자}` — Outbox 억제 키와 같은 형식 |
| title | varchar(200) | 목록 표시용 한 줄 (예: "SKU A-001 청바지 초과판매") |
| detail | text | 최근 발생 기준 상세. 재발 시 갱신 |
| status | varchar(20) | `IncidentStatus` — OPEN · ACKNOWLEDGED · RESOLVED |
| occurrence_count | integer | 발생 횟수. 재발 시 +1 |
| last_occurred_at | timestamptz | 최근 발생. 최초 발생은 `created_at` 이 담당 |
| acknowledged_at | timestamptz | 확인 시각 (nullable) |
| resolved_at | timestamptz | 해결 시각 (nullable) |
| resolution_note | varchar(1000) | 해결 메모 (nullable) |
| version | bigint | 낙관적 락 — 화면 이중 클릭 방어 |
| created_at · updated_at | timestamptz | `BaseTimeEntity` |

```sql
-- 미해결 상태의 같은 문제는 한 행뿐이다 — 목록 도배 방지의 최종 방어선 (중복 수집 방지와 같은 패턴)
CREATE UNIQUE INDEX ux_incident_open_dedupe ON incident (dedupe_key) WHERE status <> 'RESOLVED';
-- 기본 탭(미확인)과 상태 필터 목록용
CREATE INDEX ix_incident_status_last_occurred ON incident (status, last_occurred_at DESC);
```

### 상태 전이

```
OPEN(미확인) ──acknowledge()──> ACKNOWLEDGED(확인) ──resolve(note)──> RESOLVED(해결)
     └────────────────────resolve(note)─────────────────────────────────┘
```

- 1인 운영이므로 OPEN → RESOLVED 직행을 허용한다 (확인 클릭 강제는 수고만 늘린다)
- 잘못된 전이는 `INCIDENT_STATUS_INVALID` 거부
- ACKNOWLEDGED 상태에서 재발하면 상태는 유지하고 횟수만 누적 — 이미 인지한 문제다
- RESOLVED 는 최종 상태. 재발은 새 인시던트가 된다 (재오픈 없음)

## `IncidentService` — 변경 메서드는 `Propagation.MANDATORY`

| 메서드 | 동작 |
|---|---|
| `report(type, dedupeKey, title, detail)` | 미해결 건을 비관적 락으로 조회 → 있으면 누적, 없으면 생성. **호출자(감지 지점)의 트랜잭션에 참여** |
| `acknowledge(id)` / `resolve(id, note)` | 상태 전이. 웹 조율 서비스가 트랜잭션을 연다 |
| `findIncidents(status, pageable)` · `get(id)` · `countUnresolved()` | 화면 조회 (readOnly) |

`report` 의 동시성: 조회-후-삽입 사이의 경합은 부분 유니크 인덱스가 최종 방어한다. 충돌 시
감지 지점의 트랜잭션이 롤백되지만 — 수집은 커서 미전진으로 다음 주기에 재시도, 감시는 다음
주기에 재검사하므로 자가 회복된다. 상시 단일 인스턴스 운영이라 실제 발생 확률은 낮다.

## 감지 지점 연결 (2곳, 알림 경로 변경 없음)

| 지점 | 이벤트 | dedupe_key |
|---|---|---|
| `OrderIngestionService.ingest` | 오버셀 | `OVERSELL:{skuCode}` |
| `OrderIngestionService.ingest` | 미매핑 | `UNMAPPED_PRODUCT:{채널}:{상품번호}:{옵션번호}` |
| `OrderIngestionService.applyStockRetroactively` | 소급 반영 중 오버셀 | `OVERSELL:{skuCode}` |
| `StockConsistencyChecker.check` | 정합성 불일치 | `STOCK_MISMATCH:{skuCode}` |

기존 트랜잭션 안에서 `report()` 를 호출하므로 수집 실패 시 인시던트도 함께 롤백된다.
알림 억제(`enqueueIfNotRecent`)와 무관하게 인시던트는 매 발생마다 누적된다 — 알림은
스팸 방지가 목적이고 인시던트는 기록이 목적이다.

## 화면 `/monitor/incidents`

알림 이력(#32) 화면과 같은 패턴 — 서버 렌더링 + 쿼리스트링 페이징.

- 상태 탭: 전체 / 미확인 / 확인 / 해결 (기본: **미확인** — 조치 대상부터)
- 목록: 유형 · 제목 · 발생 횟수 · 최초/최근 발생 · 상태, 행 확장으로 detail·해결 메모
- 미확인 행: [확인] [해결] 버튼, 확인 행: [해결] 버튼. 해결은 메모 선택 입력
- `IncidentController` + `IncidentAdminService`(트랜잭션 열고 감사 기록) + `IncidentView`
- 감사: `INCIDENT_ACKNOWLEDGE` · `INCIDENT_RESOLVE` (AuditGroup.CHANGE)
- `ScreenNames` `/monitor/incidents` → "인시던트", 사이드바 메뉴 추가

## 오류 코드

`ErrorCode` 에 접두사 `I`(인시던트) 신설:

- `INCIDENT_NOT_FOUND("I001", NOT_FOUND, WARN)`
- `INCIDENT_STATUS_INVALID("I002", CONFLICT, WARN)` — 잘못된 상태 전이

`errors.properties` · `errors_en.properties` 각 2줄. `ErrorCodeIntegrationTest` 가 누락을 잡는다.

## 테스트

| 위치 | 내용 |
|---|---|
| `palim-incident` 단위 (Spring 없음) | 상태 전이 규칙 · 재발 누적 · 잘못된 전이 거부 |
| `palim-app` 통합 (Testcontainers PG) | `report` 생성/누적, 부분 유니크 인덱스 동작, 해결 후 재발 시 새 행, 수집·감시 경로에서 인시던트 생성 |
| 스키마 검증 | 기존 컨텍스트 로드 테스트가 `ddl-auto=validate` 로 V4 를 검증 |

## 문서 갱신

- 02-ARCHITECTURE: 모듈 구성에 `palim-incident` 추가
- 03-DOMAIN: 엔티티 표에 `Incident` 추가
- 07-DECISIONS: 인시던트 설계 결정(모듈 신설 근거 · 누적 방식 · 수동 해결) 기록
- 08-ROADMAP: 인시던트 관리 완료, 다음 작업 대시보드
