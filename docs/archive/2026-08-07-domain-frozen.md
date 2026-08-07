# 03. 도메인 모델

## 엔티티

| 모듈 | 엔티티 | 역할 |
|---|---|---|
| palim-sku | `Sku` | SKU · 현재 재고 스냅샷 · 안전재고 임계치 |
| | `StockMovement` | 재고 변동 이력 (append-only) |
| palim-order | `Order` | 채널 주문 (테이블명 `orders` — `order` 는 예약어) |
| | `OrderLine` | 주문 항목. **재고 차감의 단위** |
| palim-mapping | `ProductMapping` | 채널 상품코드 ↔ SKU |
| palim-channel | `Channel` | 채널 설정 · 수집 커서 · 실패 카운터 |
| | `ChannelCredential` | API 인증정보 (AES-GCM 암호화) |
| | `StockPushLog` | 채널 재고 전송 이력 |
| | `StockPushSetting` | 전송 안전장치 (단일 행) |
| palim-notification | `NotificationOutbox` | 발송 대기 알림 |
| | `NotificationSetting` | 알림 설정 (단일 행) |
| palim-auth | `AdminAccount` | 관리자 계정 |
| palim-audit | `AuditLog` | 감사 로그 (불변 기록, #20) |
| palim-incident | `Incident` | 인시던트 — 오버셀·정합성 불일치·미매핑의 미확인→확인→해결 관리 (#35) |

## 재고 정합성

### 스냅샷 + 이력 + 대조 검증

| 대상 | 성격 |
|---|---|
| `sku.quantity` | 현재값 스냅샷 (조회용) |
| `stock_movement` | append-only 이력 |

일 1회 대조 배치가 검산한다.

```
SUM(stock_movement.delta) == sku.quantity  →  불일치 시 텔레그램 경고
```

> 본 시스템은 스스로를 "재고의 유일한 기준"으로 정의한다. 원본을 자처하는 시스템이 자신의 불일치를 감지하지 못하면 틀어진 상태로 장기간 운영되고, 발주자는 실물 재고가 안 맞는 것을 발견한 시점에야 인지한다. 그때는 원인 추적이 불가능하다.

### 대조가 성립하기 위한 두 조건

**1. 초기 재고를 이력으로 남긴다.**

`Sku.register` 가 초기 수량을 받는데 대응 이력이 없으면 누적합이 항상 그만큼 적게 나와 **정상 상태를 매번 불일치로 오판한다.**

```java
StockMovement.ofInitialStock(skuId, quantity)   // SkuService.register 가 함께 저장한다
```

**2. 실사 조정의 `delta` 는 변경 전후의 차이다.**

절대값으로 덮어쓰는 변경이지만 이력에는 차이를 남겨야 누적합 대조가 성립한다.

```java
// quantityBefore=10, quantityAfter=50 이면 delta=40
StockMovement.ofAdjustment(skuId, quantityBefore, quantityAfter, memo)
```

### 재고 변경은 이력 기록과 짝으로 일어난다

`SkuService` 의 변경 메서드가 두 작업을 함께 수행한다. **호출자는 `StockMovement` 를 직접 만들지 않는다.**

```java
void    decreaseForSale(UUID skuId, int quantity, UUID orderLineId);  // boolean 반환 — 오버셀링 여부
void    increaseForCancel(UUID skuId, int quantity, UUID orderLineId);
void    restock(UUID skuId, int quantity, String memo);
void    dispose(UUID skuId, int quantity, String memo);
void    adjust(UUID skuId, int newQuantity, String memo);
```

분리해두면 언젠가 한쪽을 빠뜨리고, 그 순간 대조 배치가 불일치를 보고한다.

### 재고 차감은 비관적 락으로

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Sku> findForUpdateById(UUID id);
```

낙관적 락 + 재시도도 가능하나 **재시도 로직의 결함이 곧 이중 차감**이다. 일 주문 수십 건 규모에서는 경합이 사실상 없어 락 비용이 무시할 수준이다.

락 구간 안에서 채널 API 호출이나 텔레그램 발송을 하지 않는다.

### 오버셀링 — 음수 재고를 허용한다

채널 재고 동기화(F-08)는 수 분 내 반영이므로 그 사이 실재고 초과 주문이 들어온다.

| 선택지 | 결과 |
|---|---|
| 주문 저장 실패 | **데이터 유실.** 고객은 이미 결제했고 채널에 주문이 존재한다 |
| 재고 미반영 | `stock_applied = false` 로 남아 **소급 반영 대상으로 무한 재시도** |
| **음수 허용** | 사실을 정확히 표현하고 대조가 계속 성립한다 |

재고 시스템에서 음수는 "출고해야 할 빚"이다. 화면과 알림으로 즉시 인지할 수 있고, 이력 누적합과 스냅샷이 여전히 일치하므로 대조 배치가 거짓 경고를 내지 않는다.

**판매 차감만 음수를 허용한다.**

```java
void    decrease(int amount);            // 수동(폐기·분실) — 음수 거부. 입력 실수 가능성
boolean decreaseForSale(int amount);     // 판매 — 음수 허용
```

음수 도달 시 `OVERSELL` 알림을 발송한다.

## 중복 수집 방지

### 유니크 제약이 유일한 방어선

```sql
CREATE UNIQUE INDEX uk_order_line_channel
    ON order_line (channel_code, channel_order_no, channel_line_no);
```

`order_line` 에 `channel_code`·`channel_order_no` 를 주문에서 중복 저장한다. 제약을 **라인 단위**로 걸어야 하기 때문이다. 비정규화지만 이 제약이 유일한 방어선이라 감수한다.

**"조회했더니 없어서 삽입한다"만으로는 수집이 중첩되는 순간 뚫린다.** 조회는 1차 필터로만 쓰고, 최종 판정은 삽입 성공 여부다.

```java
if (orderService.existsOrderLine(...)) { skip(); }   // 1차 필터 — 성능
OrderLine line = orderService.saveOrderLine(...);    // 제약 위반 → BusinessException(ORDER_LINE_DUPLICATE)
```

### 중복 예외는 오류가 아니다

수집 커서가 구간을 겹쳐 조회하므로 같은 주문이 반복 수집되는 것이 정상이다. `ErrorCode.ORDER_LINE_DUPLICATE` 의 로그 레벨이 `DEBUG` 인 이유다.

**이 예외가 발생하면 트랜잭션은 rollback-only 가 된다.** 따라서 수집 조율은 주문 1건 단위로 트랜잭션을 연다 — 여러 주문을 한 트랜잭션에서 처리하면 중복 하나 때문에 정상 주문까지 롤백된다.

## 미매핑 주문 소급 반영

매핑되지 않은 상품의 주문도 저장한다. 데이터를 버리면 복구할 수 없다.

| 필드 | 역할 |
|---|---|
| `order_line.sku_id` | **nullable.** 미매핑이면 null |
| `order_line.stock_applied` | 재고 반영 여부. 소급 대상 판별의 근거 |

```
주문 수집 → 매핑 없음 → skuId=null 로 저장 + 미매핑 알림
         → 발주자가 매핑 등록
         → UnmappedOrderReconciler 가 stock_applied=false 대상을 찾아 소급 차감
```

부분 인덱스로 조회를 받친다.

```sql
CREATE INDEX ix_order_line_awaiting_stock ON order_line (created_at)
    WHERE sku_id IS NOT NULL AND stock_applied = false;
```

## 소프트 삭제 — 전역 적용 금지

이 도메인에는 삭제 개념이 거의 없다.

| 대상 | 처리 |
|---|---|
| 주문 | 삭제하지 않는다. 취소·반품은 상태 전이 + 재고 복원 |
| SKU | 단종은 `active` 플래그. 재고 이력이 참조하므로 물리 삭제 불가 |
| 재고 이력 · 전송 이력 | 감사 기록. 삭제 금지 |

**`@SQLRestriction` 을 `BaseTimeEntity` 에 걸지 않는다.** 전역 필터가 있으면 미매핑 주문 소급 반영에서 대상 행이 조용히 제외되고, 필터링된 사실을 인지할 방법이 없어 재고가 무증상으로 틀어진다.

개별 적용 시 유니크 제약과 충돌하므로 부분 인덱스로 해소한다.

```sql
CREATE UNIQUE INDEX uk_x_active ON x (code) WHERE deleted_at IS NULL;
```

`@SQLRestriction` 은 JPQL 에만 적용되고 네이티브 쿼리에는 적용되지 않는다.

## 옵션 식별자가 NULL 이면 유니크 제약이 무력화된다

`product_mapping.channel_option_no` 는 옵션 없는 상품에서 NULL 이다. PostgreSQL 은 NULL 을 서로 다른 값으로 취급하므로 일반 UNIQUE 제약으로는 중복이 걸러지지 않는다.

```sql
CREATE UNIQUE INDEX uk_product_mapping_channel_product
    ON product_mapping (channel_code, channel_product_no, COALESCE(channel_option_no, ''));
```

조회 쪽에도 같은 함정이 있다. **파생 쿼리 메서드는 null 파라미터를 `= null` 로 번역해 결과가 항상 빈다.** 명시적 JPQL 로 `is null` 비교를 처리한다.
