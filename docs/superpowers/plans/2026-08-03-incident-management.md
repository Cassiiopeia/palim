# 인시던트 관리 (#35) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 오버셀·재고 정합성 불일치·미매핑 상품을 미확인→확인→해결 상태로 관리하는 인시던트 도메인 모듈·감지 연결·관리 화면을 만든다.

**Architecture:** 새 도메인 모듈 `palim-incident`(엔티티+MANDATORY 서비스)를 만들고, collector·monitor 의 기존 감지 트랜잭션 안에서 `IncidentService.report()` 를 호출한다. 미해결 건은 부분 유니크 인덱스로 1행을 보장하고 재발은 횟수 누적한다. 화면은 알림 이력(#32)과 같은 서버 렌더링 패턴이다.

**Tech Stack:** Java 25 / Spring Boot 4.1 / JPA / Flyway V4 / PostgreSQL 부분 유니크 인덱스 / Thymeleaf + daisyUI / Testcontainers

## Global Constraints

- 도메인 모듈은 서로 의존하지 않는다 — `palim-incident` 는 `palim-common` 만 의존
- 변경 메서드는 `Propagation.MANDATORY` — 트랜잭션은 호출자가 연다
- 시각은 전 계층 `Instant`, 표시 직전 KST 변환
- 예외는 `BusinessException` + `ErrorCode` 만. 새 코드에 properties 2파일 각 1줄
- 커밋 메시지: `인시던트 관리 - 오버셀·정합성 불일치·미매핑 상태 관리 : {타입} : {설명} https://github.com/Cassiiopeia/palim/issues/35` — AI 흔적 금지(CI guard)
- 로컬 Gradle 실행이 막히면 push 후 GitHub Actions 로 검증한다 (프로젝트 CLAUDE.md)

---

### Task 1: palim-incident 모듈 골격 — enum·엔티티·단위 테스트·ErrorCode

**Files:**
- Create: `palim-incident/build.gradle.kts`, `palim-incident/src/main/java/kr/suhsaechan/palim/incident/{package-info,IncidentType,IncidentStatus,Incident}.java`
- Create: `palim-incident/src/test/java/kr/suhsaechan/palim/incident/IncidentTest.java`
- Modify: `settings.gradle.kts` (도메인 블록에 `include("palim-incident")`)
- Modify: `palim-common/.../error/ErrorCode.java` (접두사 I — `INCIDENT_NOT_FOUND("I001")`, `INCIDENT_STATUS_INVALID("I002")`)
- Modify: `palim-common/src/main/resources/errors.properties`, `errors_en.properties`

**Interfaces (Produces):**
- `Incident.open(IncidentType, String dedupeKey, String title, String detail, Instant occurredAt)` → `Incident` (status OPEN, occurrenceCount 1)
- `incident.recordRecurrence(String detail, Instant occurredAt)` — 횟수+1·최근발생 갱신·상태 유지
- `incident.acknowledge()` — OPEN 에서만, 그 외 `INCIDENT_STATUS_INVALID`
- `incident.resolve(String note)` — RESOLVED 아니면 허용(OPEN 직행 포함), RESOLVED 재호출 거부
- enum `IncidentType{OVERSELL, STOCK_MISMATCH, UNMAPPED_PRODUCT}` / `IncidentStatus{OPEN, ACKNOWLEDGED, RESOLVED}` — 각 `displayName()`

- [ ] 단위 테스트 작성(상태 전이·누적·거부) → 구현 → 로컬 컴파일 확인(가능 시)
- [ ] Flyway 는 Task 2 에서 — 엔티티만으로는 스키마 검증이 없다

### Task 2: 저장소·서비스·Flyway V4·통합 테스트

**Files:**
- Create: `palim-incident/.../IncidentRepository.java`, `IncidentService.java`
- Create: `palim-app/src/main/resources/db/migration/V4__incident.sql`
- Create: `palim-app/src/test/java/kr/suhsaechan/palim/integration/IncidentIntegrationTest.java`
- Modify: `palim-app/build.gradle.kts` (`testImplementation(project(":palim-incident"))`)

**Interfaces (Produces):**
- `IncidentService.report(IncidentType, String dedupeKey, String title, String detail)` → `Incident` — MANDATORY, 미해결 건 비관적 락 조회 후 누적 또는 생성
- `IncidentService.acknowledge(UUID)` / `resolve(UUID, String note)` — MANDATORY
- `IncidentService.get(UUID)` / `findIncidents(IncidentStatus|null, Pageable)` / `countUnresolved()` — readOnly
- DDL: `incident` 테이블 + `ux_incident_open_dedupe (dedupe_key) WHERE status <> 'RESOLVED'` + `ix_incident_status_last_occurred`

- [ ] 통합 테스트: report 생성/누적(1행·횟수2) · 해결 후 재발은 새 행 · 부분 유니크 인덱스 위반 · 상태 전이 영속
- [ ] `ErrorCodeIntegrationTest` 가 신규 코드 메시지 누락을 잡는다 (기존 테스트)

### Task 3: 감지 지점 연결 — collector·monitor

**Files:**
- Modify: `palim-collector/build.gradle.kts`, `palim-monitor/build.gradle.kts` (`implementation(project(":palim-incident"))`)
- Modify: `palim-collector/.../OrderIngestionService.java` — 미매핑·오버셀(수집/소급) 시 `report()`
- Modify: `palim-monitor/.../StockConsistencyChecker.java` — 불일치 시 `report()`
- Modify: `IncidentIntegrationTest.java` — 감시 경로 검증 추가

**Interfaces (Consumes):** Task 2 의 `IncidentService.report`. dedupe_key: `OVERSELL:{skuCode}` · `STOCK_MISMATCH:{skuCode}` · `UNMAPPED_PRODUCT:{채널}:{상품번호}:{옵션번호|-}`

- [ ] 기존 트랜잭션 안에서 호출 — 새 트랜잭션을 열지 않는다
- [ ] `StockConsistencyChecker.check()` 2회 실행 → 인시던트 1행·횟수 2 검증

### Task 4: 화면 — 컨트롤러·조율 서비스·뷰·템플릿·감사

**Files:**
- Create: `palim-web/src/main/java/kr/suhsaechan/palim/web/monitor/{IncidentController,IncidentAdminService,IncidentView}.java`
- Create: `palim-web/src/main/resources/templates/monitor/incidents.html`
- Modify: `palim-web/build.gradle.kts` (`implementation(project(":palim-incident"))`)
- Modify: `palim-audit/.../AuditType.java` (`INCIDENT_ACKNOWLEDGE`, `INCIDENT_RESOLVE` — CHANGE 그룹)
- Modify: `palim-web/.../audit/ScreenNames.java` (`/monitor/incidents` → "인시던트")
- Modify: `palim-web/src/main/resources/templates/layout.html` (사이드바 "인시던트")

**Interfaces (Consumes):** Task 2 서비스. 패턴은 `NotificationHistory{Controller,Service,View}` 와 동일 — 탭 기본값 OPEN, `all=true` 로 전체, POST 후 flash + redirect, 상태 변경은 `WebAuditRecorder.recordChange`

- [ ] GET `/monitor/incidents` (status·all·page) / POST `{id}/acknowledge` / POST `{id}/resolve` (resolutionNote 선택)
- [ ] 목록 정렬: `lastOccurredAt DESC`

### Task 5: 문서 갱신·검증·커밋

**Files:**
- Modify: `docs/02-ARCHITECTURE.md`(모듈 구성), `docs/03-DOMAIN.md`(엔티티 표), `docs/07-DECISIONS.md`(결정 추가), `docs/08-ROADMAP.md`(현황·다음 작업 대시보드)

- [ ] `./gradlew build` (불가 시 push 후 CI)
- [ ] pro-commit 으로 커밋 → push → PR → CI 통과 → merge

## Self-Review

- 스펙 커버리지: 모듈 신설(T1-2)·감지 연결(T3)·화면(T4)·오류코드(T1)·테스트(T1-3)·문서(T5) — 전부 대응됨
- 타입 일관성: report/acknowledge/resolve 시그니처 Task 간 동일 확인
- 실행 순서: T1→T2→T3→T4 는 컴파일 의존 순서와 일치
