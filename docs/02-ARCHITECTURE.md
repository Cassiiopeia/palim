# 02. 아키텍처

## 스택

| 영역 | 결정 |
|---|---|
| 메인 | Java 25 / Spring Boot 4.x — Gradle 멀티모듈 모놀리스 |
| 스크립트 | Python 3 (`scripts/`) — Java 가 `ProcessBuilder` 서브프로세스로 호출 |
| AI | OpenAI API (Java SDK 직접 호출, 구조화 출력) — 05-INTEGRATION |
| DB | PostgreSQL (Flyway 마이그레이션) |
| 화면 | Thymeleaf + daisyUI (07-DECISIONS 009) |
| 배포 | Docker 단일 이미지 (JRE + python3 + pip 의존성) — 06-OPERATIONS |

## 전체 구조

```
┌─ Spring Boot 모놀리스 (두뇌) ────────────────────────────┐
│  업로드 화면 · 스케줄러 · DB(처리 이력) · 텔레그램 알림      │
│  모듈 오케스트레이션 · OpenAI 호출(구조화 출력)             │
│                                                         │
│   ProcessBuilder ──▶ scripts/  (py, 손발)                │
│                      ├ parse_excel.py   (pandas 파싱)    │
│                      ├ video_fetch.py   (yt-dlp·자막)    │
│                      └ ...모듈별 추가                     │
└─────────────────────────────────────────────────────────┘
        Docker 이미지 1개 → 컨테이너 1개 (AWS 이식 = 이미지 이동)
```

**역할 분담 원칙**: 판단·흐름·화면·알림·AI 호출은 Java. py 는 **Java 에 대체재가 없는 지점**만
(pandas 엑셀 파싱, yt-dlp). AI 호출을 py 로 우회하지 않는다.

py 호출 규약(인자 배열·JSON stdout·타임아웃·스레드풀)은 04-CONVENTIONS 에 있다. 이 규약을
지키면 추후 py 서버 분리 시 ProcessBuilder 호출부를 HTTP 로 바꾸는 것으로 끝난다.

## 모듈 구분 — 활성 / 브릿지 / 동결

### 활성 (새 코드가 들어가는 곳)

| 모듈 | 역할 |
|---|---|
| `palim-automation` *(신설 예정)* | 자동화 모듈 도메인 — 작업 정의·실행 이력·결과물 |
| `palim-web` | 화면·컨트롤러 (동결 화면 제외) |
| `palim-app` | 부트스트랩·조립 |

### 브릿지 (재사용 기반 — 확장 가능)

| 모듈 | 역할 |
|---|---|
| `palim-common` | `BusinessException`·`ErrorCode`·기반 엔티티·테스트 픽스처 |
| `palim-auth` | 로그인·세션 제한·실패 잠금 |
| `palim-audit` | 감사 로그 |
| `palim-notification` | 텔레그램 발송·Outbox·알림 이력 |

### 동결 (수정 금지 — 삭제도 하지 않는다)

`palim-sku` · `palim-order` · `palim-collector` · `palim-channel` · `palim-mapping` ·
`palim-incident`

재고 시스템의 도메인 코드다. 실행 경로에서 분리되어 있고(화면 내비게이션 제거, 컨트롤러
`@Deprecated`), 향후 모듈(재고 대사 등)에서 개념 재활용 가능성이 있어 보존한다. 설계 문서는
`archive/2026-08-07-domain-frozen.md`.

## 의존 규칙 (유지)

- 도메인 모듈끼리 직접 의존하지 않는다 — 값(UUID)으로 참조
- 하위 모듈은 `implementation` 의존 — 테스트에서 직접 참조 시 명시 선언
- 여러 도메인에 걸친 조회는 `JdbcClient` (화면용 read model)

## 새 코드를 어디에 두나

| 만들려는 것 | 위치 |
|---|---|
| 자동화 모듈 로직 (파싱 결과 처리·AI 호출·리포트 생성) | `palim-automation` |
| py 스크립트 | `scripts/` (규약: 04-CONVENTIONS) |
| 업로드·실행·결과 화면 | `palim-web` |
| 새 실패 유형 | `palim-common` `ErrorCode` |
| 텔레그램 발송이 필요한 기능 | `palim-notification` 경유 — 직접 봇 API 호출 금지 |
