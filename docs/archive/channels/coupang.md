# 쿠팡 (Coupang Wing / Open API)

> 05-OPERATIONS 가 요구한 채널 연동 기록의 첫 문서다. 채널 API 는 문서와 실제 동작이 일치하지
> 않는 경우가 많고, 수개월 뒤 같은 문제를 다시 조사하는 것은 개발자 본인이다.

## 요약

| 항목 | 값 |
|---|---|
| 인증 | HMAC-SHA256 요청 서명 |
| 호출 제한 | 초당 10회. **지속 초과 시 영구 차단** |
| 기본 수집 주기 | 5분 |
| 페이징 | `nextToken` 방식 |
| 주문 시각 | **타임존 없는 KST 문자열** |
| 고정 IP 등록 | 불필요 (다른 채널과 다름) |

## 인증정보

`ChannelCredentialService` 에 아래 키로 등록한다. 값은 AES-GCM 으로 암호화되어 저장된다.

| 키 | 용도 | 발급 위치 |
|---|---|---|
| `accessKey` | 서명 · `X-Requested-By` 헤더 | Wing → 판매자 정보 → Open API 키 발급 |
| `secretKey` | 서명 | 같음. **발급 시 1회만 노출된다** |
| `vendorId` | 요청 경로 | Wing 판매자 코드 (`A` + 숫자) |

`secretKey` 는 재조회할 수 없다. 분실하면 재발급해야 하고 기존 키는 무효가 된다.

## 요청 서명

### 서명 대상 문자열

```
{signedDate}{method}{path}{query}
```

**구분자가 없다.** 그대로 이어 붙인다.

| 요소 | 주의 |
|---|---|
| `signedDate` | `yyMMdd'T'HHmmss'Z'`, **UTC 기준** |
| `method` | 대문자 |
| `path` | 쿼리스트링 제외 |
| `query` | 앞의 `?` 제외. 없으면 **빈 문자열** (null 금지) |

### 밟기 쉬운 함정

**1. `signedDate` 를 로컬 시각으로 만들면 실패한다.**

KST 환경에서 9시간 어긋나 서명이 통째로 무효가 된다. 반드시 UTC 로 포맷한다.

**2. 서명과 헤더의 `signed-date` 가 같아야 한다.**

같은 값을 두 곳에 쓴다. 서명을 만든 뒤 헤더용으로 다시 시각을 구하면 초 단위가 어긋날 수 있다.

**3. `query` 문자열이 실제 요청과 정확히 같아야 한다.**

파라미터 순서가 달라지거나 인코딩이 어긋나면 서명이 무효가 된다. `buildQuery` 가 만든 문자열을 서명과 요청에 모두 쓴다.

**4. `query` 가 `null` 이면 문자열 `"null"` 이 서명에 들어간다.**

빈 문자열로 다뤄야 한다.

### Authorization 헤더

```
CEA algorithm=HmacSHA256, access-key={accessKey}, signed-date={signedDate}, signature={hex}
```

`X-Requested-By` 헤더에도 `accessKey` 를 넣는다.

## 호출 제한 — 영구 차단 위험

**다른 채널과 실패 비용이 다르다.** 지속 초과 시 계정이 영구 차단되며, 복구는 발주자가 쿠팡에
문의해야 한다.

| 대응 | 구현 |
|---|---|
| 요청 간 최소 간격 | 기본 150ms (규정 100ms 보다 여유) |
| **페이징 순회 중에도 간격 유지** | 여기서 놓치기 쉽다 — 10페이지면 10회 호출이다 |
| 429 응답 | 즉시 예외를 던져 수집을 중단한다. 커서는 전진하지 않는다 |
| 연속 실패 | 임계치(3회) 도달 시 채널 자동 비활성화 |

## 주문 시각

응답 형식은 `yyyy-MM-dd'T'HH:mm:ss` 이고 **타임존 정보가 없다.** KST 기준이다.

```java
LocalDateTime.parse(orderedAt, COUPANG_DATE_TIME)
        .atZone(ZoneId.of("Asia/Seoul"))
        .toInstant();
```

`Instant.parse` 로 처리하면 UTC 로 해석되어 **9시간 어긋난다.** 그 값이 수집 커서 계산과 중복
판정에 쓰이므로, 어긋나면 재고가 이중 차감된다.

요청 파라미터(`createdAtFrom`, `createdAtTo`)도 KST 문자열이다.

## 응답 구조 매핑

| 쿠팡 필드 | `ChannelOrderLine` | 비고 |
|---|---|---|
| `vendorItemPackageId` | `channelLineNo` | 주문 항목 식별자. 중복 판정에 쓰인다 |
| `productId` | `channelProductNo` | 상품 단위 |
| **`vendorItemId`** | **`channelOptionNo`** | **옵션 단위. 재고 차감 대상을 결정한다** |
| `vendorItemName` | `channelProductName` | 옵션명이 포함된다 |
| `shippingCount` | `quantity` | |
| `salesPrice` | `unitPrice` | |
| `orderPrice` | `amount` | |

**`vendorItemId` 가 매핑의 핵심이다.** 쿠팡은 같은 상품의 색상·사이즈를 서로 다른
`vendorItemId` 로 구분하므로, 이 값 단위로 SKU 를 연결해야 재고가 정확히 차감된다.
`productId` 단위로 매핑하면 색상별 재고를 구분할 수 없다.

## 수집 대상 상태

`status=ACCEPT` 만 가져온다. 결제 완료 이후 단계다.

취소·반품 수집은 아직 구현하지 않았다 — F-03 이 요구하는 재고 복원을 위해 추후 추가한다.

## 응답 샘플

`palim-channel/src/test/resources/samples/coupang/` 에 보관한다.

| 파일 | 내용 |
|---|---|
| `ordersheets-page1.json` | 2건, `nextToken` 있음, 한 주문에 항목 2개 |
| `ordersheets-page2.json` | 1건, `nextToken` 빈 문자열, **미지의 필드 포함** |

**현재 파일은 규격 기반 추정치다.** 실제 응답을 받으면 이 자리에 교체한다 — 채널 API 사양
변경을 감지할 유일한 수단이다.

## 미확인 사항

발주자 인증정보(P-02)가 없어 아래는 검증하지 못했다.

- [ ] 실제 응답 필드명·구조가 위 매핑과 일치하는지
- [ ] `nextToken` 의 실제 동작 (빈 문자열 vs null vs 필드 부재)
- [ ] `status` 값의 정확한 목록
- [ ] 인수조건 A-01 (실주문 1건 알림 도달)

인증정보를 받으면 이 목록부터 확인한다.
