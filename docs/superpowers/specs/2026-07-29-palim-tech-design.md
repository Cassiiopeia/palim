# Palim — 기술 설계서

- **프로젝트명**: Palim (팔림)
- **작성일**: 2026-07-29
- **문서 상태**: 확정 (Q-00 의존 항목 별도 표기)
- **문서 성격**: [기능 명세서](2026-07-28-palim-design.md)가 정의한 **무엇을(WHAT)** 에 대해, **어떻게 만들 것인가(HOW)** 를 정의한다. 구현 시 판단 기준이 되는 문서다.

---

## 1. 결정 요약

| 영역 | 결정 |
|---|---|
| 언어 / 런타임 | Java 25 LTS (Lombok 호환 검증 실패 시 Java 21 LTS) |
| 프레임워크 | Spring Boot 4.x |
| 빌드 | Gradle (Kotlin DSL), 도메인 축 멀티모듈 10개 |
| 영속성 | JPA / Hibernate |
| 기본키 | **UUIDv7**, 애플리케이션에서 생성·할당 |
| 데이터베이스 | PostgreSQL |
| 시각 타입 | `Instant` / `timestamptz` (전 계층 통일) |
| 스키마 관리 | Flyway + `ddl-auto=validate` |
| 메시지 큐 | RabbitMQ + PostgreSQL Outbox 병행 |
| 관리자 화면 | Thymeleaf 서버 렌더링 + Spring Security |
| 알림 | Telegram Bot API |
| 코드 생성 | Lombok 사용 (`@Data`·`@Setter` 제외) |
| 배포 | GitHub Actions → GHCR → NAS `docker compose pull` |
| 외부 노출 | Cloudflare Tunnel |

---

## 2. 기술 선택 근거

### 2.1 Java + Spring Boot

본 시스템은 외주 납품물이며, 납품 후에도 개발자 1인이 장기간 단독으로 유지보수한다. 이 조건에서는 **3년 뒤에 열어봐도 즉시 이해되는 스택**이 가장 큰 가치를 가진다. 개발자의 주력 스택이 Java/Spring이므로 컨텍스트 전환 비용이 발생하지 않고, 레퍼런스와 AI 보조 지원도 가장 두텁다.

기능 측면에서도 부합한다. 본 시스템의 핵심 난이도는 **재고 정합성**에 있으며, 트랜잭션 경계·락·스케줄링에 대해 Spring이 제공하는 기본기가 그대로 필요하다.

### 2.2 빌드 도구 — Gradle

개발 PC가 사내 내부망 환경으로 Gradle 배포판 및 플러그인 포털 접근이 제한되나, **빌드는 GitHub Actions에서 수행하므로 실제 배포 경로에 영향이 없다.** 로컬 빌드가 필요한 경우는 별도 네트워크 환경에서 수행한다.

> **설계 근거**: 내부망 제약을 이유로 Maven을 선택하면, 제약이 사라진 뒤에도 그 선택이 계속 남는다. 제약은 빌드 환경에서 해소하고 도구 선택은 프로젝트 요건으로 판단한다.

### 2.3 Java 버전 — 착수 시 검증 항목

Lombok은 javac 내부 API에 의존하여 JDK 메이저 버전 상승 시 대응이 지연될 수 있다. 프로젝트 셋업 첫 단계에서 다음을 검증한다.

```
Java 25 + Spring Boot 4.x + Lombok 최신 → 컴파일 통과 여부
```

통과하지 못하면 **Java 21 LTS로 하향한다.** `record`·`sealed`·pattern matching은 Java 21에 모두 포함되어 있어 본 문서가 규정하는 코드 스타일은 변경되지 않는다.

### 2.4 관리자 화면 — Thymeleaf

화면이 7개이고 사용자가 관리자 1명이다. SPA를 분리하면 인증 처리·빌드 파이프라인·CORS 관리가 추가되지만 얻는 것이 없다. 서버 렌더링으로 단일 배포 단위를 유지한다.

---

## 3. 모듈 구조

모듈은 **도메인 축**으로 나눈다. 원칙은 하나다 — **도메인 모듈은 자기 도메인만 안다.** 여러 도메인을 관통하는 책임은 조율 계층이 갖는다.

| 모듈 | 책임 | 의존 |
|---|---|---|
| `palim-common` | `UuidV7`, `BaseTimeEntity`, 공통 예외, 통합 테스트 픽스처 | 없음 |
| `palim-auth` | 관리자 계정, 비밀번호 해싱 | common |
| `palim-sku` | SKU · 재고 수량 · 안전재고 임계치 · 재고 이력 | common |
| `palim-order` | 주문 · 주문 항목 | common |
| `palim-channel` | 채널 설정 · API 인증정보 · 수집 커서 · 채널 어댑터 | common |
| `palim-mapping` | 채널 상품코드 ↔ SKU 매핑 | common |
| `palim-notification` | Outbox · 알림 설정 · 텔레그램 발송 | common |
| `palim-collector` | 수집 스케줄러 · **수집 조율 트랜잭션** | 도메인 전부 |
| `palim-web` | Thymeleaf 화면 · Security · 화면용 조회 | 도메인 전부 |
| `palim-app` | 진입점 · 설정 조립 · Flyway | collector, web |

### 3.1 모듈을 나누는 목적 — jar 크기가 아니다

`bootJar`는 실행 가능한 단일 파일이므로 전체 런타임 의존성의 **합집합**을 포함한다. 모듈별로 의존성을 좁혀도 최종 jar 크기는 줄지 않는다. 크기가 줄어드는 것은 모듈을 별도 프로세스로 배포할 때이며, 본 시스템은 단일 배포다(4.5 참조).

목적은 다음 네 가지다.

| 이득 | 내용 |
|---|---|
| **컴파일 타임 격리** | `palim-sku`에 web 의존성을 주지 않으면 재고 도메인에서 `@Controller`·`HttpServletRequest`를 **쓸 수 없다.** 규칙을 문서가 아니라 컴파일러가 강제한다 |
| 빌드 속도 | 변경된 모듈과 그 하위만 재컴파일된다 |
| 의존 방향 강제 | 역참조·순환이 컴파일 단계에서 차단된다 |
| 분리 여지 확보 | 경계가 이미 존재하면 향후 프로세스 분리가 파일 이동 수준이 된다 |

즉 얻으려는 것은 작은 jar가 아니라 **실수할 수 없는 구조**다.

### 3.2 의존 방향

```
palim-app
   ├──→ palim-collector ──→ 도메인 모듈들
   └──→ palim-web       ──→ 도메인 모듈들

모든 도메인 모듈 ──→ palim-common
도메인 모듈 ──X──→ 다른 도메인 모듈        (금지)
```

`palim-app`이 최상위인 이유는 진입점이 여기 있어 하위 모듈의 빈을 스캔해야 하기 때문이다. `collector`와 `web`은 목적이 달라 서로를 의존하지 않는다.

부트 jar는 `palim-app`에서만 생성한다. 나머지는 라이브러리 jar로 빌드된다.

### 3.3 도메인 간 참조는 UUID 값으로만

```java
// palim-order 의 OrderLine
private UUID skuId;              // O
// private Sku sku;              // X — palim-sku 의존이 생긴다
```

기본키가 애플리케이션에서 생성하는 UUIDv7이라 저장 전에 식별자가 확정되므로, 다른 모듈의 엔티티를 몰라도 참조를 표현할 수 있다.

> **설계 근거**: `@GeneratedValue` 시퀀스라면 저장 전에 식별자가 없어 이 구조가 훨씬 번거로워진다. 4.1의 UUIDv7 결정이 모듈 경계 유지에서 실질적 이득으로 돌아오는 지점이다.

DB 레벨 외래키는 Flyway로 정상 부여한다. 모듈 독립성은 코드 차원의 문제이고 데이터 정합성은 별개다. 단 미매핑 주문을 저장해야 하므로(F-04) `order_line.sku_id`는 **nullable**이다.

### 3.4 트랜잭션은 `palim-collector`가 연다

주문 수집 1건이 도메인 4개를 관통하며, 이 전체가 한 트랜잭션이어야 한다.

```java
// palim-collector
@Transactional
public void ingest(ChannelOrder channelOrder) {
    UUID skuId = mappingService.resolveSkuId(...);    // palim-mapping
    boolean isNew = orderService.saveIfAbsent(...);   // palim-order (유니크 제약)
    if (!isNew) return;                              // 중복 → 재고 미차감 (A-02)
    if (skuId != null) {
        skuService.decrease(skuId, quantity);        // palim-sku (비관적 락)
    }
    outboxService.enqueue(...);                      // palim-notification
}
```

도메인 서비스는 자체 트랜잭션을 열지 않고 참여만 한다.

> **설계 근거**: 도메인이 각자 트랜잭션을 열면 재고는 차감됐는데 주문은 롤백되는 상태가 발생한다. 트랜잭션을 여는 지점을 한 곳으로 고정해야 재고 정합성이 보장된다.

### 3.5 채널 어댑터의 격리

`palim-channel`은 **주문·재고 도메인을 알지 못한다.** 어댑터는 공통 `record`(예: `ChannelOrder`, `ChannelProduct`)만 반환하며, 이를 엔티티로 변환하는 책임은 `palim-collector`에 있다.

> **설계 근거**: 기능 명세서 4.2는 *"신규 채널 추가 시 기존 코드에 영향을 주지 않는다"* 고 규정한다. 이를 문서상의 약속이 아니라 **빌드 시스템이 강제하는 사실**로 만든다. 쿠팡 어댑터에서 `Sku`를 참조하면 컴파일이 실패한다.

채널마다 모듈을 분리하지는 않는다. 어댑터 간에 HTTP 클라이언트·재시도 정책·rate limiter를 공유하므로, 분리하면 공유 코드를 다시 공통 모듈로 추출해야 하고 얻는 격리 수준에 비해 관리 대상만 증가한다. 채널 간 분리는 패키지 단위로 수행한다.

### 3.6 텔레그램 발송을 별도 모듈로 두지 않는 이유

발송은 Outbox를 소유한 `palim-notification` 도메인의 외부 연동 인프라다. 별 모듈로 빼면 Outbox 상태 전이와 발송이 모듈 경계를 넘나들게 되어 오히려 결합이 늘어난다.

반면 수집 조율(`palim-collector`)은 여러 도메인을 관통하는 독립된 책임이므로 별 모듈로 유지한다. 화면(`palim-web`)과 목적이 전혀 달라 한 모듈에 합치면 배치 코드와 화면 코드가 섞인다.

---

## 4. 코드 스타일 규약

### 4.1 기본키 — UUIDv7

랜덤 UUIDv4를 기본키로 사용하면 B-tree 인덱스 삽입 위치가 무작위가 되어 페이지 분할·WAL 증가·캐시 미스가 누적된다. 주문 테이블과 같이 계속 적재되는 테이블에서 특히 불리하다.

**UUIDv7**(RFC 9562)은 상위 48비트가 밀리초 타임스탬프이므로 시간순으로 정렬된다.

```java
public final class UuidV7 {
    private static final NoArgGenerator GEN = Generators.timeBasedEpochGenerator();

    public static UUID generate() { return GEN.generate(); }

    private UuidV7() { }
}
```

- 생성은 **애플리케이션에서 수행한다.** 저장 이전에 식별자가 확정되므로 로그·알림 메시지·Outbox 레코드에 즉시 사용할 수 있으며, ORM에 종속되지 않는다.
- 컬럼 타입은 PostgreSQL 네이티브 `uuid`(16바이트)를 사용한다. `varchar(36)`을 사용하지 않는다.

### 4.2 UUID 기본키 사용 시 필수 조건 — `@Version`

애플리케이션이 식별자를 미리 할당하면 Spring Data JPA의 `save()`가 기존 엔티티로 판단하여 `persist()`가 아닌 `merge()`를 호출한다. 그 결과 INSERT마다 불필요한 SELECT가 선행된다.

`@Version` 속성이 존재하면 Spring Data JPA는 해당 값의 `null` 여부로 신규 엔티티를 판정하므로 이 문제가 해소된다.

```java
@Version
private Long version;   // long이 아닌 wrapper Long이어야 한다
```

`@Version`은 재고 수동 조정과 자동 차감이 충돌할 때의 갱신 유실 감지에도 함께 사용된다. 즉 **낙관적 락과 신규 판정 두 가지 목적에서 필수**다.

### 4.3 시각 타입 — `Instant` 고정

전 계층에서 `Instant`를 사용하고 데이터베이스 컬럼은 `timestamptz`로 정의한다. `LocalDateTime`은 리포트 출력 직전의 표시 변환에만 사용한다.

> **설계 근거**: 채널 API는 KST와 UTC를 혼용하여 응답한다. 주문 시각에 타임존 모호성이 유입되면 중복 판정과 수집 커서 계산이 어긋나고, 이는 곧 재고 이중 차감으로 이어진다.

### 4.4 엔티티 작성 규칙

```java
@Entity
@Getter
@Table(name = "sku")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sku extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int safetyThreshold;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private Long version;

    private Sku(String code, String name, int quantity, int safetyThreshold) {
        this.id = UuidV7.generate();
        this.code = code;
        this.name = name;
        this.quantity = quantity;
        this.safetyThreshold = safetyThreshold;
    }

    public static Sku register(String code, String name, int initialQuantity, int safetyThreshold) {
        if (initialQuantity < 0) {
            throw new IllegalArgumentException("초기 재고는 0 이상이어야 합니다");
        }
        return new Sku(code, name, initialQuantity, safetyThreshold);
    }

    public void decrease(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("차감 수량은 1 이상이어야 합니다");
        }
        if (quantity - amount < 0) {
            throw new InsufficientStockException(code, quantity, amount);
        }
        this.quantity -= amount;
    }

    public boolean isBelowThreshold() {
        return quantity < safetyThreshold;
    }

    public void discontinue() {
        this.active = false;
    }
}
```

| 항목 | 규칙 | 근거 |
|---|---|---|
| 기본 생성자 | `@NoArgsConstructor(access = PROTECTED)` | `private`이면 Hibernate 프록시 생성이 실패한다. `public`이면 빈 객체 생성이 허용된다 |
| 객체 생성 | 정적 팩토리 (`Sku.register(...)`) | 검증을 통과하지 않은 인스턴스가 존재할 수 없음을 보장한다 |
| `@Builder` | 필드 5개 이상이며 선택 필드가 있는 경우에만, **생성자에** 부착 | 클래스에 부착하면 `id`·감사 필드까지 외부에서 지정 가능해진다 |
| Lombok | `@Getter` 사용. `@Data`·`@Setter`·`@AllArgsConstructor` 금지 | setter가 열리면 도메인 규칙이 우회된다 |
| 상태 변경 | `decrease()` 등 의미가 드러나는 메서드 | 변경 지점이 좁아져 추적 범위가 축소된다 |
| 연관관계 | `@ManyToOne(fetch = LAZY)`. `@OneToMany`는 필요한 경우에만 | N+1 및 의도치 않은 cascade 차단 |
| Enum | `@Enumerated(EnumType.STRING)` 필수 | ORDINAL은 상수 순서 변경 시 데이터가 손상된다 |

### 4.5 공통 상위 클래스

```java
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
```

부트 진입점에 `@EnableJpaAuditing`을 선언한다. 누락 시 전 필드가 `null`로 저장된다.

`@CreatedBy`·`@LastModifiedBy`는 사용하지 않는다. 기능 명세서 F-09가 관리자 계정을 1개로 규정하므로 모든 행의 값이 동일하여 의미가 없다.

`isEdited`·`isCreated` 형태의 boolean 플래그는 사용하지 않는다. `createdAt`·`updatedAt`으로 유도되는 파생 정보이며, 두 값이 어긋날 여지만 만든다.

### 4.6 소프트 삭제 — 전역 적용 금지

본 시스템의 도메인에는 삭제 개념이 거의 없다.

| 대상 | 처리 |
|---|---|
| 주문 | 삭제하지 않는다. 취소는 상태 전이 + 재고 복원(F-03) |
| SKU | 단종은 `active` 플래그. 재고 이력이 참조하므로 물리 삭제 불가 |
| 재고 이력·전송 이력 | 감사 기록이므로 삭제를 금지한다 |
| 상품 매핑 | 해제 이력이 필요한 경우에 한해 개별 적용 |

`@SQLRestriction`을 상위 클래스에 전역 적용하지 않는다.

> **설계 근거**: 전역 필터를 적용하면 F-04의 *"매핑 완료 후 미매핑 주문의 재고 소급 반영"* 처리 시 대상 행이 조용히 제외될 수 있다. 필터링된 사실을 인지할 수단이 없으므로 재고가 무증상으로 틀어진다.

개별 적용이 필요한 경우 `@Where`가 아닌 **`@SQLRestriction`** 을 사용한다(`@Where`는 Hibernate 6.3에서 deprecated).

소프트 삭제 적용 시 유니크 제약과 충돌하므로 PostgreSQL 부분 인덱스로 해소한다.

```sql
CREATE UNIQUE INDEX uk_channel_product_active
    ON channel_product (channel_code, channel_product_no)
    WHERE deleted_at IS NULL;
```

`@SQLRestriction`은 JPQL에만 적용되고 네이티브 쿼리에는 적용되지 않는다.

### 4.7 DTO

DTO는 `record`로 작성한다. Lombok을 사용하지 않는다.

```java
public record SkuCreateRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank String name,
        @PositiveOrZero int initialQuantity,
        @PositiveOrZero int safetyThreshold
) { }

public record SkuResponse(UUID id, String code, String name, int quantity, boolean belowThreshold) {

    public static SkuResponse from(Sku sku) {
        return new SkuResponse(sku.getId(), sku.getCode(), sku.getName(),
                sku.getQuantity(), sku.isBelowThreshold());
    }
}
```

---

## 5. 데이터 정합성 설계

### 5.1 중복 방지 — 데이터베이스 제약으로 보장 (A-02)

```sql
CREATE UNIQUE INDEX uk_order_line_channel
    ON order_line (channel_code, channel_order_no, channel_line_no);
```

**재고 차감은 행이 실제로 INSERT된 경우에만 실행한다.** 제약 위반이 발생하면 이미 처리된 주문이므로 차감 없이 건너뛴다.

> **설계 근거**: "조회 후 없으면 삽입" 방식은 수집이 중첩되는 순간 뚫린다. 삽입 성공 여부를 판정 기준으로 삼아야 A-02가 구조적으로 보장된다.

### 5.2 재고 차감 — 비관적 락

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select s from Sku s where s.id = :id")
Optional<Sku> findForUpdateById(@Param("id") UUID id);
```

낙관적 락과 재시도 조합도 가능하나, **재시도 로직의 결함이 곧 이중 차감으로 이어진다.** 일 주문 수십 건 규모에서는 경합이 사실상 발생하지 않으므로 비관적 락의 비용이 무시할 수준이며, 처리 흐름이 단순해진다.

트랜잭션은 짧게 유지한다. 락 구간 안에서 채널 API 호출이나 텔레그램 발송을 수행하지 않는다.

### 5.3 재고 스냅샷 + 이력 + 대조 검증

| 대상 | 성격 |
|---|---|
| `sku.quantity` | 현재값 스냅샷 (조회용) |
| `stock_movement` | append-only 이력 (`sku_id`, `delta`, `reason`, `ref_type`, `ref_id`, `created_at`) |

여기에 **일 1회 대조 배치**를 추가한다.

```
SUM(stock_movement.delta) == sku.quantity  →  불일치 시 텔레그램 경고
```

> **설계 근거**: 기능 명세서 3-F03은 본 시스템을 *"재고의 유일한 기준"* 으로 정의한다. 원본을 자처하는 시스템이 자신의 불일치를 감지하지 못하면, 틀어진 상태로 장기간 운영된다. 발주자는 실물 재고 불일치를 발견한 시점에야 인지하게 되며 그 시점에는 원인 추적이 불가능하다. 하루 1회 자기 검산 비용은 쿼리 1건이다.

이 항목은 기능 명세서에 없는 추가 안전장치이며, 발주자 대면 기능이 아닌 내부 방어 수단이다.

### 5.4 수집 커서 — 구간 중첩

채널별 `collected_until`을 저장하되, 조회 구간은 **`[collected_until - 10분, now]`** 로 한다. 중복은 5.1의 유니크 제약이 흡수한다.

> **설계 근거**: 채널 API는 주문 시각이 지연 반영되는 경우가 있어, 구간을 정확히 이어붙이면 경계에서 주문이 누락된다. 중복은 제약으로 차단되지만 누락은 감지되지 않는다.

---

## 6. 채널 어댑터

### 6.1 호출 제한 준수

Resilience4j RateLimiter를 채널별로 구성하고, 설정값은 데이터베이스에서 로드한다(기능 명세서 F-01에 따라 웹 관리자에서 변경 가능).

| 채널 | 제한 |
|---|---|
| 쿠팡 | 초당 10회, 지속 초과 시 **영구 차단** |
| 네이버 | 초당 2회 |
| G마켓/옥션 | 주문조회 5초당 1회 |
| 11번가 / 롯데온 | 미공개 — 보수적 기본값 적용 |

429 응답 또는 인증 실패가 연속 발생하면 지수 백오프를 적용하고 텔레그램으로 경고한다. 쿠팡은 영구 차단 위험이 있어 임계 도달 시 해당 채널 수집을 자동 중단한다.

### 6.2 인증정보 보관

채널 API 인증정보는 **데이터베이스에 저장하고 애플리케이션 레벨에서 AES-GCM으로 암호화**한다. 마스터키는 환경변수로 주입하며 컨테이너 이미지에 포함하지 않는다.

기능 명세서 F-09가 *"채널별 API 인증정보 등록"* 화면을 요구하므로 환경변수 단독 방식은 채택할 수 없다. 발주자가 키를 갱신해도 재배포가 불필요하다.

### 6.3 응답 회귀 감지

각 채널의 **실제 API 응답 샘플을 테스트 리소스로 보관**하고 파싱 회귀 테스트를 구성한다.

> **설계 근거**: 기능 명세서 7.1이 인정하듯 채널 API 사양 변경은 통제 불가 요인이다. 변경을 감지할 수 있는 유일한 수단은 보관된 실제 응답과의 비교다. 샘플 없이 수개월 뒤 파싱 오류를 만나면 원인 파악에만 수일이 소요된다.

---

## 7. 알림 파이프라인 — Outbox + RabbitMQ

기능 명세서 4.3은 다음을 규정한다.

> *"알림 발송 대상은 PostgreSQL에 먼저 기록한 후 큐에 투입한다."*

이는 Outbox 패턴에 해당하며, RabbitMQ와 택일 관계가 아니라 **순차 결합** 관계다.

```
주문 저장 + outbox 행 삽입        ← 동일 트랜잭션 (원자적)
        │ 커밋
        ▼
outbox relay → RabbitMQ 발행
        ▼
notifier 소비 → 텔레그램 발송 → outbox 상태 SENT
```

| 장애 지점 | 결과 |
|---|---|
| RabbitMQ 중단 | Outbox 행이 잔존하여 재기동 시 이어서 발송 (**A-14 충족**) |
| 텔레그램 API 실패 | 상태가 SENT로 전이되지 않아 재시도 대상으로 유지 |
| 애플리케이션 재기동 | 미발송 Outbox 행을 기동 시 재적재 |

Outbox 없이 RabbitMQ만 사용하면, 주문 커밋 후 큐 발행이 실패하는 순간 알림이 영구 소실된다.

발송 인터페이스는 `palim-notification` 내부의 포트로 분리하여, 기능 명세서 4.4가 규정한 *"큐 제품 교체 시 해당 계층만 교체"* 요건을 충족한다. 호출자는 Outbox에 기록하는 것까지만 알고, RabbitMQ 발행은 구현체가 담당한다.

---

## 8. 테스트 전략

| 대상 | 방식 |
|---|---|
| 재고 차감 동시성 | Testcontainers(PostgreSQL) — 실제 락 동작 검증 |
| 중복 주문 처리 | Testcontainers — 유니크 제약 위반 경로 검증 |
| 채널 어댑터 | WireMock + 보관된 실제 응답 샘플 |
| Outbox 재발송 | RabbitMQ 중단 후 재기동 시나리오 |
| 도메인 규칙 | 순수 단위 테스트 (Spring 컨텍스트 없음) |

인메모리 데이터베이스는 사용하지 않는다. 비관적 락·부분 인덱스·`timestamptz` 동작이 PostgreSQL과 다르면 검증의 의미가 없다.

---

## 9. 운영 및 유지보수

### 9.1 배포

```
git push → GitHub Actions 빌드 → GHCR 이미지 push (태그 = 커밋 SHA)
                                        ↓
                        NAS: docker compose pull && up -d
```

이미지 태그를 커밋 SHA로 고정하므로 롤백은 이전 태그를 지정하여 pull하는 것으로 완료된다. NAS는 빌드를 수행하지 않는다.

### 9.2 백업

`pg_dump`를 일 1회 수행하여 NAS의 별도 볼륨에 보관하고, 성공·실패를 텔레그램으로 통보한다.

> **설계 근거**: 본 시스템에서 데이터베이스 유실은 복구가 어려운 것이 아니라 **불가능하다.** 주문은 채널에서 재수집할 수 있으나, 수동 입력한 초기 재고·실사 조정·입고 이력은 다른 어디에도 존재하지 않는다.

### 9.3 자기 감시

별도 관측 스택(Prometheus, Grafana 등)을 구성하지 않는다. 본 시스템은 이미 운영자에게 도달하는 경로(텔레그램)를 보유하므로, 시스템 자체의 장애도 동일 경로로 통보한다.

| 감시 대상 | 통보 조건 |
|---|---|
| 채널 수집 | 연속 실패 시 (기능 명세서 A-10) |
| Outbox | 미발송 건수가 임계치 초과 시 |
| 데이터베이스 | 연결 실패 시 |
| 애플리케이션 | 재기동 발생 시 |
| 재고 정합성 | 5.3의 대조 배치 불일치 시 |

Spring Boot Actuator health 정보를 위 경로에 연결한다.

> **설계 근거**: 본 시스템의 목적 자체가 *"접속하지 않아도 알 수 있게 하는 것"* 이다. 운영 감시를 위해 대시보드에 접속해야 한다면 목적과 모순된다.

### 9.4 로그

JSON 구조화 로그를 사용하고 볼륨에 마운트하여 로테이션한다. 로그 수집 스택은 구성하지 않는다.

### 9.5 채널 연동 기록

채널별 인증 절차·응답 특성·실제로 겪은 함정을 `docs/channels/<채널명>.md` 에 기록한다.

> **설계 근거**: 채널 API는 문서와 실제 동작이 일치하지 않는 경우가 많다. 수개월 뒤 동일 문제를 다시 조사하는 것은 개발자 본인이다.

---

## 10. 개발 단계

| 단계 | 범위 | 대응 기능 |
|---|---|---|
| Phase 1 | 프로젝트 골격 + **쿠팡 1채널** 수집 → 텔레그램 알림 | F-01, F-02 |
| Phase 2 | 재고·매핑·안전재고 | F-03, F-04, F-05 |
| Phase 3 | 채널 확장 (네이버 → 나머지) | F-01 확장 |
| Phase 4 | 일일 리포트, 엑셀 내보내기 | F-06, F-07 |
| Phase 5 | 채널 재고 전송 (시뮬레이션 모드 선행 운영) | F-08 |
| Phase 6 | AI 리포트 요약, 대화형 조회 | F-10, F-11 |

### 10.1 Phase 1을 수직 슬라이스로 구성하는 이유

채널 1개를 수집부터 알림까지 관통하면 인증·페이징·응답 형식·호출 제한을 실제로 모두 겪게 되며, 이후 채널은 동일한 틀의 반복이 된다. 반대로 어댑터 6종을 먼저 구현하면 첫 알림 발송 시점에 6종을 동시에 수정하게 된다.

### 10.2 Phase 5를 후반에 배치하는 이유

F-08은 **채널에 데이터를 기록하는 유일한 기능**이며, 오류 시 매출 손실로 직결된다(기능 명세서 4.2 설계 근거 참조). 재고 계산이 수 주간 정확했다는 근거가 축적된 후에 활성화한다. 시뮬레이션 모드 선행 운영은 선택이 아닌 필수 절차로 취급한다.

---

## 11. 리스크 및 미해결 항목

### 11.1 Q-00 — 개발 착수 전 확인 필요

기능 명세서 6.1이 **최우선 확인 사항**으로 규정한 Q-00(발주자가 기존 통합솔루션 사용 중에도 수작업이 남는 지점)이 미확정 상태다.

Q-00이 **후보 A(통합솔루션 → 이카운트 이관을 수동 수행)** 로 확인될 경우, 기능 명세서 F-07 및 2.2 제외 항목의 개정이 필요하다. ERP 연동이 제외 기능이 아니라 핵심 기능이 된다.

| 항목 | 영향 |
|---|---|
| 본 문서의 모듈 구조 | **영향 없음** — `palim-collector`와 대칭 위치에 내보내기 어댑터가 추가된다 |
| 개발 범위 및 견적 | **변경됨** |
| Phase 1~3 착수 | **영향 없음** — 어느 답이 나와도 필요한 작업이다 |

**Phase 4 진입 전까지 Q-00에 대한 답이 확정되어야 한다.**

### 11.2 기타 리스크

| 리스크 | 대응 |
|---|---|
| Lombok의 Java 25 미지원 | 착수 시 검증, 실패 시 Java 21로 하향 (2.3 참조) |
| 쿠팡 API 영구 차단 | RateLimiter + 임계 도달 시 자동 수집 중단 (6.1 참조) |
| 채널 API 사양 변경 | 실제 응답 샘플 기반 회귀 테스트 (6.3 참조) |
| 고정 IP 요건 | 기능 명세서 4.4에 따라 수집 실패 감지·경고로 대응 |

---

## 12. 관련 문서

- [기능 명세서](2026-07-28-palim-design.md) — 개발 범위 및 기능 정의 (WHAT)
