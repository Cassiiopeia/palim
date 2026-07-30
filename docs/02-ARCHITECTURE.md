# 02. 아키텍처

## 스택

| 영역 | 결정 |
|---|---|
| 언어 / 런타임 | Java 25 LTS |
| 프레임워크 | Spring Boot 4.1.x |
| 빌드 | Gradle 9.x (Kotlin DSL), 멀티모듈 10개 |
| 영속성 | JPA / Hibernate |
| 기본키 | UUIDv7, 애플리케이션에서 생성 |
| 데이터베이스 | PostgreSQL 17 |
| 시각 | `Instant` / `timestamptz` |
| 스키마 | Flyway + `ddl-auto=validate` |
| 알림 전달 | PostgreSQL Outbox (메시지 큐 미사용) |
| 화면 | Thymeleaf + Tailwind CSS 4 / daisyUI 5 + Spring Security |
| 알림 | Telegram Bot API |
| 배포 | GitHub Actions → GHCR → NAS `docker compose pull` |
| 외부 노출 | Cloudflare Tunnel |

선택 근거는 [07-DECISIONS](07-DECISIONS.md) 를 본다.

## 모듈 구성

```
palim-common          UuidV7 · BaseTimeEntity · ErrorCode · BusinessException
├─ palim-auth         관리자 계정 · 비밀번호 해싱
├─ palim-sku          SKU · 재고 · 안전재고 · 재고 이력
├─ palim-order        주문 · 주문 항목
├─ palim-channel      채널 설정 · 인증정보 · 수집 커서 · 채널 어댑터
├─ palim-mapping      채널 상품코드 ↔ SKU 매핑
└─ palim-notification Outbox · 알림 설정 · 텔레그램 발송

palim-collector       수집 스케줄러 · 수집 조율 트랜잭션      외부 -> 내부
palim-monitor         정합성 대조 · 안전재고 감시 · 일일 리포트  내부 -> 알림
palim-web             Thymeleaf 화면 · Security · 화면용 조회
palim-app             진입점 · 설정 조립 · Flyway
```

조율 계층이 셋이다. 방향과 실패 대응이 달라 분리했다.

| 계층 | 책임 | 방향 | 실패 시 |
|---|---|---|---|
| `palim-collector` | 채널 주문을 내부 상태에 반영 | 외부 → 내부 | 커서를 되돌려 재시도 |
| `palim-monitor` | 내부 상태를 점검해 알림 | 내부 → 알림 | 다음 주기에 다시 본다 |
| `palim-web` | 사용자 조작을 상태에 반영 | 사용자 → 내부 | 오류 응답 |

## 의존 규칙

```
palim-app
   ├──→ palim-collector ──→ 도메인 모듈들
   ├──→ palim-monitor   ──→ 도메인 모듈들
   └──→ palim-web       ──→ 도메인 모듈들

모든 도메인 모듈 ──→ palim-common
도메인 모듈 ──X──→ 다른 도메인 모듈        (금지)
```

`palim-app` 이 최상위인 이유는 진입점이 여기 있어 하위 모듈의 빈을 스캔해야 하기 때문이다. `collector`·`monitor`·`web` 은 목적이 달라 서로를 의존하지 않는다.

부트 jar 는 `palim-app` 에서만 만든다. 나머지는 라이브러리 jar 다.

## 규칙 1 — 도메인 모듈은 서로를 의존하지 않는다

```java
// palim-order 의 OrderLine
private UUID skuId;              // O
// private Sku sku;              // X — palim-sku 의존이 생긴다
```

기본키가 애플리케이션에서 생성하는 UUIDv7 이라 저장 전에 식별자가 확정되므로, 다른 모듈의 엔티티를 몰라도 참조를 표현할 수 있다.

DB 외래키는 정상 부여한다. **모듈 독립성은 코드 차원이고 데이터 정합성은 별개다.** 단 미매핑 주문을 저장해야 하므로 `order_line.sku_id` 는 nullable 이다.

> 자동 검증 장치는 없다. ArchUnit 을 도입하지 않았으므로 `build.gradle.kts` 와 `package-info.java` 의 규칙을 사람이 지켜야 한다.

## 규칙 2 — 트랜잭션은 조율 계층이 연다

트랜잭션을 여는 곳은 **조율 계층 셋(`collector`·`monitor`·`web`)뿐**이다. 도메인 서비스의 변경 메서드는 `Propagation.MANDATORY` 로 참여만 한다.

```java
// palim-collector
@Transactional(propagation = Propagation.REQUIRES_NEW)
public IngestResult ingest(ChannelOrder channelOrder, Instant collectedAt) {
    UUID skuId = mappingService.resolveSkuId(...);    // palim-mapping
    OrderLine line = orderService.saveOrderLine(...); // palim-order
    skuService.decreaseForSale(skuId, qty, line.getId()); // palim-sku
    outboxService.enqueue(...);                       // palim-notification
}
```

자세한 근거는 [04-CONVENTIONS](04-CONVENTIONS.md) 의 트랜잭션 절을 본다.

## 규칙 3 — 여러 도메인에 걸친 조회는 SQL 로 한다

모듈이 분리되어 JPA 조인이 불가능하다. `palim-web` 에서 `JdbcClient` 로 직접 SQL 을 쓰고 결과를 `record` 로 매핑한다.

쓰기는 도메인 모듈(JPA), 읽기는 SQL 이다.

## 모듈을 나누는 목적

**jar 크기가 아니다.** `bootJar` 는 실행 가능한 단일 파일이라 전체 런타임 의존성의 합집합을 포함한다. 모듈별로 의존성을 좁혀도 최종 크기는 줄지 않는다.

| 이득 | 내용 |
|---|---|
| **컴파일 타임 격리** | `palim-sku` 에 web 의존성을 주지 않으면 재고 도메인에서 `@Controller` 를 **쓸 수 없다** |
| 빌드 속도 | 변경된 모듈과 그 하위만 재컴파일 |
| 의존 방향 강제 | 역참조·순환이 컴파일 단계에서 차단 |
| 분리 여지 | 경계가 있으면 향후 프로세스 분리가 파일 이동 수준 |

얻으려는 것은 작은 jar 가 아니라 **실수할 수 없는 구조**다.

## 부트스트랩

기동 시 초기화는 **각 도메인 모듈이 자기 것을 소유**한다. `palim-app` 은 도메인 모듈을 `implementation` 으로만 의존하므로 서비스를 직접 볼 수 없고, 여기에 의존을 추가하면 모듈 격리가 무너진다.

| 컴포넌트 | 위치 | 초기화 대상 |
|---|---|---|
| `ChannelBootstrap` | palim-channel | 채널 7개(비활성), `StockPushSetting` |
| `NotificationBootstrap` | palim-notification | `NotificationSetting` |
| `AdminAccountBootstrap` | palim-auth | 관리자 계정 |

`ApplicationRunner` 를 쓴다. `ApplicationReadyEvent` 리스너에서 던진 예외는 로그만 남고 애플리케이션이 계속 떠서, **설정이 없는 상태로 운영에 들어갈 수 있다.**
