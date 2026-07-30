# Palim — 작업 지침

다채널 판매 알림·재고 관리 시스템. Java 25 / Spring Boot 4.x / Gradle 멀티모듈.

**코드를 쓰기 전에 관련 문서를 읽는다.** 이 파일은 요약이고, 판단 근거는 `docs/` 에 있다.

| 하려는 일 | 읽을 문서 |
|---|---|
| 무엇을 만드는지 파악 | `docs/01-REQUIREMENTS.md` |
| 새 모듈·클래스를 어디 둘지 | `docs/02-ARCHITECTURE.md` |
| 엔티티·재고 로직 작성 | `docs/03-DOMAIN.md` |
| 코드 스타일·예외·트랜잭션 | `docs/04-CONVENTIONS.md` |
| 채널 어댑터·수집·알림 | `docs/05-INTEGRATION.md` |
| 배포·설정·보안 | `docs/06-OPERATIONS.md` |
| **"왜 이렇게 되어 있나"** | `docs/07-DECISIONS.md` |
| 다음에 할 일 | `docs/08-ROADMAP.md` |

---

## 절대 하지 말 것

아래는 **재고 정합성이나 보안을 직접 깨뜨린다.** 예외 없이 지킨다.

### 1. 도메인 모듈끼리 의존하지 않는다

```java
// palim-order 에서
private UUID skuId;              // O — 값 참조
// private Sku sku;              // X — palim-sku 의존이 생긴다
```

도메인 간 협력이 필요하면 조율 계층(`palim-collector`·`palim-monitor`·`palim-web`)이 처리한다.
→ `docs/02-ARCHITECTURE.md`

### 2. 도메인 서비스에 `@Transactional` 을 그냥 붙이지 않는다

변경 메서드는 **`Propagation.MANDATORY`** 다. 트랜잭션은 호출자가 연다.

```java
@Transactional(propagation = Propagation.MANDATORY)   // O
// @Transactional                                     // X
// (애너테이션 없음)                                    // X — auto-commit 으로 조용히 동작한다
```

애너테이션을 생략하면 재고 변경과 이력 기록이 **각각 커밋**되어 중간 실패 시 정합성이 깨진다.
→ `docs/04-CONVENTIONS.md`

### 3. 재고를 바꿀 때 이력을 따로 만들지 않는다

`SkuService` 의 변경 메서드가 `StockMovement` 를 함께 기록한다. **호출자가 직접 만들지 않는다.**

```java
skuService.decreaseForSale(skuId, qty, orderLineId);   // O — 이력이 함께 남는다
// sku.decrease(qty); stockMovementRepository.save(...) // X — 언젠가 한쪽을 빠뜨린다
```

→ `docs/03-DOMAIN.md`

### 4. 시각에 `LocalDateTime` 을 쓰지 않는다

전 계층 `Instant`, DB `timestamptz`. 표시 직전에만 변환한다.

채널 API 가 KST/UTC 를 섞어 주므로, 타임존 모호성이 들어오면 중복 판정과 수집 커서가 어긋나 **재고가 이중 차감된다.**

### 5. 예외 클래스를 새로 만들지 않는다

`BusinessException` 하나만 쓰고 구분은 `ErrorCode` 로 한다.

```java
throw new BusinessException(ErrorCode.SKU_NOT_FOUND, skuId);   // O
// public class SkuNotFoundException extends ...                // X
```

새 실패 유형이 필요하면 `ErrorCode` enum 에 한 줄, `errors.properties` 와 `errors_en.properties` 에 각 한 줄을 더한다.
→ `docs/04-CONVENTIONS.md`

### 6. 소프트 삭제를 상위 클래스에 전역 적용하지 않는다

`@SQLRestriction` 을 `BaseTimeEntity` 에 걸면, 미매핑 주문 소급 반영에서 대상 행이 **조용히 필터링돼 재고가 무증상으로 틀어진다.**

### 7. 채널 인증정보를 화면이나 로그에 노출하지 않는다

`ChannelCredentialService` 경계 밖으로 평문도 암호문도 나가지 않는다. 화면에는 키 이름만 보여준다.

### 8. 커밋에 AI 흔적을 남기지 않는다

`Co-Authored-By`, `Generated with`, 🤖 등 일절 금지.

---

## 자주 틀리는 것

### Spring Boot 4 는 Boot 3 관례가 깨진다

| 항목 | Boot 3 | Boot 4 |
|---|---|---|
| Testcontainers | BOM 이 버전 관리 | **관리하지 않는다.** BOM 직접 지정 |
| Flyway | `flyway-core` 만으로 자동 구성 | **`spring-boot-flyway` 필요** |
| Jackson | `com.fasterxml.jackson` | **`tools.jackson`** (Jackson 3, 예외도 unchecked) |

자동 구성이 필요한 기술을 추가할 때는 `spring-boot-{기술}` 모듈이 별도로 있는지 먼저 확인한다.

### record 컴포넌트와 같은 이름의 정적 팩토리는 만들 수 없다

컴포넌트 `boolean success` 가 있으면 `success()` 는 accessor 로 취급되어 `boolean` 을 반환해야 한다. `of()`·`from()`·`sent()` 처럼 겹치지 않는 이름을 쓴다.

### `implementation` 은 테스트 컴파일 classpath 로도 전이되지 않는다

`palim-app` 이 하위 모듈을 `implementation` 으로 의존하므로, 그 모듈의 라이브러리를 직접 참조하려면 **해당 의존성을 명시 선언**해야 한다. `api` 로 뚫어서 우회하면 모듈별 의존성 최소화가 무너진다.

---

## 빌드 · 검증

```bash
./gradlew build          # 전체 빌드 + 테스트
./gradlew :palim-app:bootJar
```

**로컬에서 Gradle 배포판 다운로드가 막힐 수 있다.** 그 경우 push 하고 GitHub Actions 결과로 검증한다 — 이 구조는 의도된 것이다.

통합 테스트는 Testcontainers 로 실제 PostgreSQL 을 띄운다. 인메모리 DB 를 쓰지 않는다 — 비관적 락·부분 인덱스·`timestamptz` 동작이 달라 검증 의미가 없다.

---

## 작업 흐름

1. 이슈 생성 → `YYYYMMDD_#번호_제목` 브랜치
2. 구현
3. 커밋 — `{이슈제목} : {타입} : {설명} {이슈URL}`
4. push → CI 통과 확인
5. PR → 머지(merge commit)

**설계 판단을 바꿨으면 `docs/07-DECISIONS.md` 에 항목을 추가한다.** 문서와 코드가 어긋나면 문서가 거짓말이 된다.
