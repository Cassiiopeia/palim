# 04. 코드 규약

## 기본키 — UUIDv7

```java
public final class UuidV7 {
    private static final NoArgGenerator GEN = Generators.timeBasedEpochGenerator();
    public static UUID generate() { return GEN.generate(); }
}
```

- **애플리케이션에서 생성한다.** 저장 전에 식별자가 확정되어야 로그·알림·Outbox 에 즉시 쓸 수 있고, 도메인 모듈 간 참조를 값으로 표현할 수 있다
- 컬럼은 PostgreSQL 네이티브 `uuid`(16바이트). `varchar(36)` 을 쓰지 않는다
- UUIDv4 를 쓰지 않는다 — 삽입 위치가 무작위여서 인덱스 페이지 분할·WAL 증가가 누적된다

### `@Version` 은 필수다

애플리케이션이 식별자를 미리 할당하면 Spring Data JPA 의 `save()` 가 기존 엔티티로 오판해 `merge()` 를 호출하고, **INSERT 마다 불필요한 SELECT 가 선행된다.**

```java
@Version
private Long version;   // long 이 아니라 wrapper Long
```

Spring Data JPA 는 version 속성이 있으면 그 값의 null 여부로 신규를 판정한다. 낙관적 락(수동 재고 조정 충돌 감지)에도 함께 쓰인다.

## 시각 — `Instant` 고정

전 계층 `Instant`, DB `timestamptz`. `LocalDateTime` 은 표시 직전 변환에만 쓴다.

> 채널 API 가 KST 와 UTC 를 섞어 응답한다. 타임존 모호성이 유입되면 중복 판정과 수집 커서 계산이 어긋나고, 그것이 곧 재고 이중 차감이다.

예외는 **하루 중 시점**을 표현하는 값이다. `NotificationSetting` 의 `quietHours`·`dailyReportTime` 은 `LocalTime` 이다.

표시할 때는 KST 로 변환한다. 문구에 UTC 시각이 나가면 주문 시각을 오해한다.

## 엔티티

```java
@Getter
@Entity
@Table(name = "sku")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sku extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Version
    private Long version;

    private Sku(String code, ...) {
        this.id = UuidV7.generate();
        this.code = code;
    }

    public static Sku register(String code, ...) {
        if (initialQuantity < 0) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_AMOUNT, initialQuantity);
        }
        return new Sku(code, ...);
    }

    public void decrease(int amount) { ... }   // 의미가 드러나는 메서드
}
```

| 항목 | 규칙 | 근거 |
|---|---|---|
| 기본 생성자 | `@NoArgsConstructor(access = PROTECTED)` | `private` 이면 Hibernate 프록시 생성이 실패한다 |
| 객체 생성 | 정적 팩토리 | 검증을 통과하지 않은 인스턴스가 존재할 수 없음을 보장 |
| `@Builder` | 필드 5개 이상 + 선택 필드가 있을 때만, **생성자에** 부착 | 클래스에 붙이면 `id`·감사 필드까지 외부에서 지정 가능해진다 |
| Lombok | `@Getter` 만. `@Data`·`@Setter`·`@AllArgsConstructor` 금지 | setter 가 열리면 도메인 규칙이 우회된다 |
| 상태 변경 | `decrease(amount)` 처럼 의미 있는 메서드 | 변경 지점이 좁아져 추적 범위가 줄어든다 |
| 연관관계 | `@ManyToOne(fetch = LAZY)`. `@OneToMany` 는 필요할 때만 | N+1 과 의도치 않은 cascade 차단 |
| Enum | `@Enumerated(EnumType.STRING)` **필수** | ORDINAL 은 상수 순서가 바뀌면 데이터가 손상된다 |
| 감사 필드 | `BaseTimeEntity` 상속 | — |

`@CreatedBy`·`@LastModifiedBy` 는 쓰지 않는다. 관리자 계정이 1개라 모든 행의 값이 같다.

`isEdited`·`isCreated` 같은 boolean 플래그도 쓰지 않는다. `createdAt != updatedAt` 으로 유도되는 파생 정보이며 두 값이 어긋날 여지만 만든다.

## DTO — record

```java
public record SkuCreateRequest(
        @NotBlank @Size(max = 50) String code,
        @PositiveOrZero int initialQuantity
) { }

public record SkuResponse(UUID id, String code, int quantity) {
    public static SkuResponse from(Sku sku) { ... }
}
```

Lombok 을 쓰지 않는다.

> **record 컴포넌트와 같은 이름의 정적 팩토리는 만들 수 없다.** 컴포넌트 `boolean success` 가 있으면 `success()` 는 accessor 로 취급되어 `boolean` 을 반환해야 한다는 컴파일 오류가 난다. `of()`·`from()`·`sent()` 처럼 겹치지 않는 이름을 쓴다.

## 트랜잭션

### 도메인 서비스의 변경 메서드는 `MANDATORY`

```java
@Transactional(propagation = Propagation.MANDATORY)
public boolean decreaseForSale(UUID skuId, int quantity, UUID orderLineId) { ... }

@Transactional(readOnly = true)
public Sku getById(UUID skuId) { ... }
```

트랜잭션을 여는 곳은 **`palim-collector`(수집)와 `palim-web`(화면 조작)뿐**이다.

> 애너테이션을 그냥 생략하면 호출자가 트랜잭션을 잊었을 때 재고 변경과 이력 기록이 **각각 커밋**되어, 중간 실패 시 정합성이 깨진 채 조용히 넘어간다. MANDATORY 는 그 실수를 즉시 예외로 드러낸다. 컴파일 단계에서 막을 수는 없지만 첫 실행에서 반드시 드러나며, 조용히 잘못 동작하는 것보다 낫다.

조회는 `readOnly = true` 다. 화면에서 단순 조회마다 트랜잭션을 열도록 강제하면 불편하다.

> **부작용**: 조회한 엔티티는 변경 메서드의 트랜잭션 밖이므로 dirty checking 대상이 아니다. 변경은 반드시 변경 메서드를 통해야 한다.

### 외부 API 를 트랜잭션 안에서 기다리지 않는다

채널 API 호출이나 텔레그램 발송을 포함하는 계층은 트랜잭션을 열지 않고, 상태 기록만 별도 빈에 위임한다.

| 계층 | 상태 기록 위임 대상 |
|---|---|
| `ChannelCollectRunner` | `CollectStateService` |
| `NotificationRelay` | `OutboxStateWriter` |

### 주문 1건 = 트랜잭션 1개

`OrderIngestionService.ingest` 는 `REQUIRES_NEW` 다. 중복 예외가 트랜잭션을 rollback-only 로 만들기 때문에, 여러 주문을 한 트랜잭션에서 처리하면 중복 하나가 정상 주문까지 롤백시킨다.

**호출자는 이 서비스와 다른 빈이어야 한다.** 같은 클래스 내 호출은 프록시를 타지 않아 전파 설정이 무효가 된다.

## 예외 — 하나의 예외, 코드로 구분

```java
throw new BusinessException(ErrorCode.SKU_NOT_FOUND, skuId);
```

**예외 클래스를 새로 만들지 않는다.** 구분은 `ErrorCode` 가 담당한다.

| 구성 | 위치 |
|---|---|
| `BusinessException` | palim-common. 유일한 비즈니스 예외 |
| `ErrorCode` | palim-common. 코드 · HTTP 상태 · 로그 레벨 |
| `errors.properties` / `errors_en.properties` | palim-common. 메시지 외부화 |
| `ErrorMessageResolver` | palim-common. 로케일별 문구 조립 |
| `GlobalExceptionHandler` | palim-web. `@RestControllerAdvice` |

### 새 실패 유형 추가 방법

1. `ErrorCode` enum 에 한 줄 — 접두사 + 세 자리 (`C` 공통, `S` SKU, `O` 주문, `M` 매핑, `H` 채널, `N` 알림, `A` 인증)
2. `errors.properties` 에 한 줄
3. `errors_en.properties` 에 한 줄

`ErrorCodeIntegrationTest` 가 코드 중복·메시지 누락·접두사 규칙을 자동 검증한다.

### 로그 레벨을 ErrorCode 가 정한다

전역 핸들러가 예외마다 조건 분기로 레벨을 정하면 새 코드가 추가될 때마다 핸들러를 고쳐야 한다.

```java
ORDER_LINE_DUPLICATE("O003", HttpStatus.CONFLICT, LogLevel.DEBUG)
```

중복 수집은 정상 흐름 제어이므로 경고로 남으면 안 된다. enum 에 `DEBUG` 만 적으면 핸들러는 그대로 둔다.

### 응답 형식

```json
{
  "errorCode": "SKU_NOT_FOUND",
  "code": "S001",
  "errorMessage": "SKU를 찾을 수 없습니다: SKU-001",
  "status": 404,
  "path": "/api/skus/SKU-001",
  "timestamp": "2026-07-30T02:30:00Z",
  "details": null
}
```

클라이언트는 `errorCode` 로 분기한다. 메시지 문자열로 판단하면 문구 변경에 깨지고 다국어에서는 불가능하다.

예상하지 못한 예외는 500 을 반환하며 **내부 메시지를 응답에 담지 않는다.** 스택 트레이스나 SQL 이 노출되면 공격 표면이 된다.

### 메시지 로케일

```yaml
spring.messages.fallback-to-system-locale: false   # 반드시 false
```

MessageSource 는 `요청 로케일 → 시스템 로케일 → 기본 번들` 순으로 찾는다. `true` 면 **서버 OS 로케일이 사용자에게 나가는 언어를 결정한다.**

## 테스트

| 대상 | 방식 |
|---|---|
| 도메인 규칙 | 순수 단위 테스트 (Spring 컨텍스트 없음) |
| 스키마 · 제약 · 정합성 | Testcontainers PostgreSQL 통합 테스트 |
| 채널 어댑터 | WireMock + 보관된 실제 응답 샘플 |

**인메모리 데이터베이스를 쓰지 않는다.** 비관적 락·부분 인덱스·`timestamptz` 동작이 PostgreSQL 과 다르면 검증 의미가 없다.

통합 테스트 베이스는 `palim-common` 의 테스트 픽스처에 있다.

```kotlin
testImplementation(testFixtures(project(":palim-common")))
```

컨테이너는 JVM 당 1회 기동하는 singleton 이다. `@Container` 로 클래스 단위 lifecycle 을 맡기면 **상속한 첫 테스트가 끝날 때 컨테이너가 중지되어 이후 테스트가 실패한다.**
