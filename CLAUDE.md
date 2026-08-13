# Palim — 작업 지침

**AI 업무자동화 플랫폼.** 발주사의 반복 업무(리포트·분석·콘텐츠 생성)를 모듈 단위로 자동화한다.
Java 25 / Spring Boot 4.x 모놀리스 + `scripts/` Python 서브프로세스.

> **2026-08 방향 전환**: 기존 재고 알림 시스템은 발주사 협의 결과 **동결**되었다(07-DECISIONS 023).
> 재고 도메인 코드(sku·order·collector·channel·mapping·incident)는 삭제하지 않되 **수정도 하지
> 않는다.** 새 기능은 자동화 모듈로만 추가한다.

**코드를 쓰기 전에 관련 문서를 읽는다.** 이 파일은 요약이고, 판단 근거는 `docs/` 에 있다.

| 하려는 일 | 읽을 문서 |
|---|---|
| 무엇을 만드는지 파악 | `docs/01-REQUIREMENTS.md` |
| 새 모듈·클래스를 어디 둘지 | `docs/02-ARCHITECTURE.md` |
| 코드 스타일·예외·py 규약 | `docs/04-CONVENTIONS.md` |
| AI·외부 API·알림 연동 | `docs/05-INTEGRATION.md` |
| 배포·설정·Docker·AWS | `docs/06-OPERATIONS.md` |
| **"왜 이렇게 되어 있나"** | `docs/07-DECISIONS.md` |
| 다음에 할 일 | `docs/08-ROADMAP.md` |
| 인증·암호화·감사·공급망 보안 | `docs/09-SECURITY.md` |

---

## 절대 하지 말 것

### 1. 이 저장소는 PUBLIC 이고, **남이 가져다 쓸 코드**다

포트폴리오로 공개하며 다른 사람이 자기 환경에 올려 쓸 수 있다(권리는 LICENSE 로 주장한다).
그래서 두 가지가 동시에 성립해야 한다.

- **민감정보가 한 줄도 없어야 한다.** 한 번 공개되면 커밋을 지워도 이력에 남는다
- **특정 환경에 묶인 값이 없어야 한다.** 호스트·포트·경로·계정·네트워크 이름은 전부 설정으로
  뺀다. 남이 Secret 만 자기 값으로 채우면 그대로 돌아가야 한다. 값을 파일에 적고 싶어지면
  그것이 Secret 이나 config 에 있어야 할 것은 아닌지 먼저 따진다

커밋 전 민감정보를 확인한다:

| 대상 | 규칙 |
|---|---|
| 비밀값(키·토큰·비밀번호) | 어떤 파일에도 리터럴 금지. 항상 `${환경변수}` 로만 |
| **인프라 식별정보** | 서버 호스트명·DDNS·공인 IP·SSH 포트·NAS 내부 경로·운영 도메인·서버 계정명을 **코드·문서·주석·테스트·이슈·PR·커밋 메시지 어디에도 쓰지 않는다.** 「운영 서버」·「NAS」·「운영 도메인」 처럼 일반 명사로 쓰고, 실제 값은 GitHub Secret 과 로컬 config 에만 둔다 |
| `application-prod.yml` / `application-dev.yml` | gitignore 대상. `-f` 로도 커밋하지 않는다. 형태는 `.example` 에만 |
| **발주사 식별정보** | 상호·브랜드·제품명·계정명·채널 URL 을 코드·문서·테스트·이슈 어디에도 쓰지 않는다. "발주사" 로 통칭 |
| 발주사 실데이터 | 업로드 엑셀·이미지·리뷰 원문 커밋 금지. 테스트는 합성 데이터로 |
| `docs/somansa/` (사내·발주사 자료) | gitignore + CI 가 차단 |
| 사내 레거시 코드 | 이식 금지. 개념만 가져와 새로 작성. 사내 제품명·호스트·이슈번호 금지 — 필요 시 "레거시 관리자 시스템" 통칭 |

**왜 인프라 정보까지 막는가** — 비밀값이 없어도 "어느 호스트의 몇 번 포트에 무엇이 떠 있고
관리자 화면 주소는 무엇인지"가 모이면 그 자체가 공격 지도다. 비밀번호를 뚫는 것보다 열려 있는
문을 찾는 쪽이 언제나 싸다. 배포 문서를 쓸 때 특히 새기 쉬우니, 값을 적고 싶어지면 그 값이
Secret 이나 config 에 있어야 할 것은 아닌지 먼저 따진다.

### 2. 커밋에 AI 흔적을 남기지 않는다

`Co-Authored-By`, `Generated with`, 🤖, `noreply@anthropic.com` 등 일절 금지. **CI `guard` 잡이
커밋 메시지를 검사해 위반 시 빌드를 실패시킨다** — 걸리면 해당 커밋을 reword 한다.

### 3. 동결 도메인을 수정하지 않는다

`palim-sku`·`palim-order`·`palim-collector`·`palim-channel`·`palim-mapping`·`palim-incident` 는
동결이다. 버그가 보여도 고치지 않는다(실행되지 않는 코드다). 브릿지 모듈(auth·audit·
notification·common·web 골격)만 확장 대상이다.

### 4. py 스크립트 호출 규약을 어기지 않는다

```java
new ProcessBuilder(List.of("python3", script, arg1, arg2))   // O — 인자 배열
// Runtime.exec("python3 " + script + " " + userInput)        // X — 커맨드 인젝션
```

- 스크립트는 **stdout 에 JSON 만**, 사람용 메시지는 stderr. 종료코드 0/1
- `PYTHONIOENCODING=utf-8` 고정 + Java 읽기 UTF-8 명시 (Windows cp949 함정)
- `waitFor(타임아웃)` + 초과 시 `destroyForcibly()` 필수
- 스크립트 실행은 전용 스레드풀(크기 2) — 동시 실행 폭주 방지
- 사용자 입력(URL 등)은 검증 후 인자로만. 쉘 문자열 조립 금지
→ `docs/04-CONVENTIONS.md`

### 5. 예외 클래스를 새로 만들지 않는다

`BusinessException` + `ErrorCode` 만 쓴다. 새 실패 유형은 `ErrorCode` enum 한 줄 +
`errors.properties`/`errors_en.properties` 각 한 줄. → `docs/04-CONVENTIONS.md`

### 6. AI 호출 경계를 지킨다

- AI 출력은 항상 **구조화 출력(json_schema)** 으로 받아 코드가 검증한다. AI 출력 문자열에 따라
  임의 동작을 실행하지 않는다 (프롬프트 인젝션 방어)
- API 키는 사용량 한도(hard limit)를 걸어둔 키만 쓴다
- **반복 처리(리포트·분석·배치)에는 필요한 컬럼만 보낸다** — 개인 식별자·전화번호는 마스킹
- **연동 정의를 만들 때는 원본 파일 전체를 보낸다.** 컬럼 구조와 값 모양을 봐야 매핑 초안이
  쓸모 있기 때문이다. 대신 AI 는 **정의 생성 시 1회만** 호출하고, 확정된 정의로 도는 반복
  실행에는 개입하지 않는다 — 매일 도는 경로로는 데이터가 한 번도 나가지 않는다

### 7. 시각에 `LocalDateTime` 을 쓰지 않는다

전 계층 `Instant`, DB `timestamptz`. 표시 직전에만 변환한다.

---

## 자주 틀리는 것

### Spring Boot 4 는 Boot 3 관례가 깨진다

| 항목 | Boot 3 | Boot 4 |
|---|---|---|
| Testcontainers | BOM 이 버전 관리 | **관리하지 않는다.** BOM 직접 지정 |
| Flyway | `flyway-core` 만으로 자동 구성 | **`spring-boot-flyway` 필요** |
| Jackson | `com.fasterxml.jackson` 전체 | **`databind`·`core` 만 `tools.jackson`** — 애노테이션은 `com.fasterxml.jackson.annotation` 그대로. 예외는 unchecked `JacksonException` |

자동 구성이 필요한 기술을 추가할 때는 `spring-boot-{기술}` 모듈이 별도로 있는지 먼저 확인한다.

### record 컴포넌트와 같은 이름의 정적 팩토리는 만들 수 없다

컴포넌트 `boolean success` 가 있으면 `success()` 는 accessor 로 취급된다. `of()`·`from()` 처럼
겹치지 않는 이름을 쓴다.

### 운영 PostgreSQL 은 **14** 다 — 15+ 문법을 쓰면 배포에서만 죽는다

운영 DB 는 NAS 의 공용 PostgreSQL **14.15** 이고, 다른 프로젝트 20여 개가 같은 인스턴스를
쓰므로 우리 사정으로 올릴 수 없다. 마이그레이션은 **PG14 문법만** 쓴다.

| 쓰면 안 되는 것 | 대신 |
|---|---|
| `NULLS NOT DISTINCT` (15+) | 자연키 컬럼을 `NOT NULL DEFAULT ''`(정수는 `0`)로 두고 평범한 유니크 인덱스 |
| `MERGE` (15+) | `INSERT ... ON CONFLICT` |
| `ANY_VALUE` (16+), `JSON_TABLE` (17+) | 쓰지 않는다 |

**Testcontainers 도 `postgres:14-alpine` 으로 고정되어 있다.** 이 값을 올리면 상위 버전 전용
문법이 테스트를 통과하고 배포에서만 터진다 — 실제로 그렇게 한 번 겪었다. `docker-compose.yml`
의 로컬 DB 도 같은 버전이다. 셋은 항상 같이 움직인다.

자연키에 `COALESCE` 표현식 인덱스를 쓰는 것도 피한다. `StandardModelWriter` 가
`ON CONFLICT` 컬럼 목록을 평문으로 조립하므로 표현식 인덱스와 매칭되지 않아
**적재 첫 실행에서** `no unique or exclusion constraint matching` 으로 죽는다.

### `JdbcClient` 에는 `Instant` 를 바인딩할 수 없다

`timestamptz` 컬럼에는 **`OffsetDateTime`** 을 넘긴다. PostgreSQL 의 `count`·`sum` 은 `bigint`
이므로 `record` 컴포넌트가 `int` 면 `count(*)::int` 로 캐스팅한다.

### `implementation` 은 테스트 컴파일 classpath 로도 전이되지 않는다

하위 모듈의 라이브러리를 직접 참조하려면 해당 의존성을 명시 선언한다. `api` 로 뚫지 않는다.

---

## 빌드 · 검증

```bash
./gradlew build          # 전체 빌드 + 테스트
./gradlew :palim-app:bootJar
```

**로컬에서 Gradle 배포판 다운로드가 막힐 수 있다.** 그 경우 push 하고 GitHub Actions 결과로
검증한다 — 이 구조는 의도된 것이다.

통합 테스트는 Testcontainers 로 실제 PostgreSQL 을 띄운다. 인메모리 DB 를 쓰지 않는다.

py 스크립트 수정 후에는 로컬에서 스크립트 단독 실행(JSON 출력 확인)까지만 한다.

## 작업 흐름

1. 이슈 생성 → `YYYYMMDD_#번호_제목` 브랜치
2. 구현
3. 커밋 — `{이슈제목} : {타입} : {설명} {이슈URL}`
4. push → CI 통과 확인
5. PR → 머지(merge commit)

**설계 판단을 바꿨으면 `docs/07-DECISIONS.md` 에 항목을 추가한다.**
