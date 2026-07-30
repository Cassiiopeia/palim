package kr.suhsaechan.palim.channel.adapter.coupang;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.channel.adapter.ChannelOrder;
import kr.suhsaechan.palim.channel.adapter.ChannelOrderCollector;
import kr.suhsaechan.palim.channel.adapter.ChannelOrderLine;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 쿠팡 주문 수집 어댑터.
 *
 * <h2>실패를 빈 목록으로 반환하지 않는다</h2>
 *
 * <p>가장 중요한 규칙이다. 인증 실패나 호출 제한 초과를 빈 목록으로 반환하면 조율 계층이
 * <b>"주문이 없다"로 오판해 커서를 전진시키고, 그 구간의 주문이 영구 유실된다.</b>
 * 반드시 예외를 던진다.
 *
 * <h2>호출 제한 — 영구 차단 위험</h2>
 *
 * <p>쿠팡은 초당 10회 제한이며 지속 초과 시 <b>계정이 영구 차단</b>된다. 차단되면 발주자가
 * 쿠팡에 문의해야 복구되므로 실패 비용이 다른 채널과 다르다.
 *
 * <p>요청 간 최소 간격을 두어 제한을 지키며, <b>페이징 순회 중에도 같은 간격을 적용한다</b> —
 * 여기서 놓치기 쉽다. 한 번의 수집이 10페이지면 10회 호출이므로 간격 없이 돌리면 즉시 초과한다.
 *
 * <h2>주문 시각을 Instant 로 정규화한다</h2>
 *
 * <p>쿠팡은 주문 시각을 <b>KST 문자열</b>로 준다. 그대로 파싱하면 UTC 로 해석되어 9시간
 * 어긋나고, 그 값이 수집 커서 계산과 중복 판정에 쓰이면 재고가 이중 차감된다(04-CONVENTIONS).
 */
@Slf4j
@Component
public class CoupangOrderCollector implements ChannelOrderCollector {

    private static final String ACCESS_KEY = "accessKey";
    private static final String SECRET_KEY = "secretKey";
    private static final String VENDOR_ID = "vendorId";

    private static final String ORDER_SHEET_PATH_TEMPLATE =
            "/v2/providers/openapi/apis/api/v4/vendors/%s/ordersheets";

    /** 쿠팡 응답의 주문 시각 형식. 타임존 정보가 없고 KST 기준이다. */
    private static final DateTimeFormatter COUPANG_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final ZoneId COUPANG_ZONE = ZoneId.of("Asia/Seoul");

    /** 수집 대상 주문 상태. 결제 완료 이후 단계만 가져온다. */
    private static final String TARGET_STATUS = "ACCEPT";

    private final RestClient restClient;
    private final CoupangProperties coupangProperties;

    public CoupangOrderCollector(CoupangProperties coupangProperties) {
        this.coupangProperties = coupangProperties;

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
        requestFactory.setReadTimeout(coupangProperties.timeout());

        this.restClient = RestClient.builder()
                .baseUrl(coupangProperties.apiBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public ChannelCode channelCode() {
        return ChannelCode.COUPANG;
    }

    @Override
    public List<ChannelOrder> collect(Instant from, Instant to, Map<String, String> credentials) {
        String vendorId = require(credentials, VENDOR_ID);
        String accessKey = require(credentials, ACCESS_KEY);
        String secretKey = require(credentials, SECRET_KEY);

        String path = ORDER_SHEET_PATH_TEMPLATE.formatted(vendorId);
        List<ChannelOrder> collected = new ArrayList<>();

        String nextToken = null;
        int page = 0;

        do {
            if (page > 0) {
                // 페이징 순회 중에도 간격을 지킨다. 여기서 놓치면 호출 제한을 즉시 초과한다.
                sleepBetweenRequests();
            }
            if (++page > coupangProperties.maxPages()) {
                // nextToken 이 예상과 다르게 동작하면 무한 루프가 되고, 그 상태로 호출 제한을
                // 초과하면 계정이 차단된다. 상한에서 멈추고 경고를 남긴다.
                log.warn("쿠팡 페이징 상한 {} 도달 — 남은 주문이 있을 수 있습니다. 구간 [{} ~ {}]",
                        coupangProperties.maxPages(), from, to);
                break;
            }

            CoupangOrderResponse response = requestPage(path, from, to, nextToken, accessKey, secretKey);
            response.data().forEach(sheet -> toChannelOrder(sheet).ifPresent(collected::add));
            nextToken = response.hasNextPage() ? response.nextToken() : null;

        } while (nextToken != null);

        log.debug("쿠팡 수집 완료 — 주문 {}건, 페이지 {}장, 구간 [{} ~ {}]",
                collected.size(), page, from, to);
        return collected;
    }

    // ------------------------------------------------------------------

    private CoupangOrderResponse requestPage(String path, Instant from, Instant to,
                                             String nextToken, String accessKey, String secretKey) {
        String query = buildQuery(from, to, nextToken);
        String signedDate = CoupangSigner.signedDate(Instant.now());
        String authorization = CoupangSigner.authorizationHeader(
                "GET", path, query, signedDate, accessKey, secretKey);

        try {
            CoupangOrderResponse response = restClient.get()
                    .uri(path + "?" + query)
                    .header("Authorization", authorization)
                    .header("X-Requested-By", accessKey)
                    .retrieve()
                    .body(CoupangOrderResponse.class);

            if (response == null) {
                throw new BusinessException(ErrorCode.CHANNEL_API_FAILED, "쿠팡");
            }
            return response;

        } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests exception) {
            // 호출 제한 초과. 지속되면 영구 차단이므로 즉시 중단해 커서를 유지한다.
            log.error("쿠팡 호출 제한 초과 — 수집을 중단합니다. 요청 간격 설정을 확인하세요.");
            throw new BusinessException(ErrorCode.CHANNEL_API_FAILED, exception, "쿠팡");

        } catch (org.springframework.web.client.HttpClientErrorException exception) {
            // 401/403 은 서명 또는 인증정보 문제다. 빈 목록으로 넘기면 주문이 유실된다.
            log.error("쿠팡 인증 실패 — {} {}", exception.getStatusCode(),
                    exception.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.CHANNEL_API_FAILED, exception, "쿠팡");

        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.CHANNEL_API_FAILED, exception, "쿠팡");
        }
    }

    /**
     * 쿼리스트링을 만든다.
     *
     * <p>서명 대상에 그대로 쓰이므로 <b>여기서 만든 문자열과 실제 요청의 쿼리가 같아야 한다.</b>
     * 순서가 달라지거나 인코딩이 어긋나면 서명이 무효가 된다.
     */
    private String buildQuery(Instant from, Instant to, String nextToken) {
        UriComponentsBuilder builder = UriComponentsBuilder.newInstance()
                .queryParam("createdAtFrom", formatForRequest(from))
                .queryParam("createdAtTo", formatForRequest(to))
                .queryParam("status", TARGET_STATUS)
                .queryParam("maxPerPage", coupangProperties.pageSize());

        if (nextToken != null) {
            builder.queryParam("nextToken", nextToken);
        }
        return builder.build().getQuery();
    }

    /** 쿠팡 요청 파라미터는 KST 기준 문자열이다. */
    private static String formatForRequest(Instant instant) {
        return COUPANG_DATE_TIME.format(instant.atZone(COUPANG_ZONE));
    }

    private java.util.Optional<ChannelOrder> toChannelOrder(CoupangOrderResponse.OrderSheet sheet) {
        if (sheet.orderItems().isEmpty()) {
            log.warn("쿠팡 주문 {} 에 항목이 없어 건너뜁니다.", sheet.orderId());
            return java.util.Optional.empty();
        }

        List<ChannelOrderLine> lines = sheet.orderItems().stream()
                .map(item -> new ChannelOrderLine(
                        String.valueOf(item.vendorItemPackageId()),
                        String.valueOf(item.productId()),
                        // 옵션 단위 식별자. 쿠팡은 색상·사이즈를 서로 다른 vendorItemId 로 구분하며
                        // 이 값이 재고 차감 대상을 결정한다.
                        String.valueOf(item.vendorItemId()),
                        item.vendorItemName(),
                        item.shippingCount(),
                        item.salesPrice(),
                        item.orderPrice()))
                .toList();

        return java.util.Optional.of(new ChannelOrder(
                ChannelCode.COUPANG,
                String.valueOf(sheet.orderId()),
                parseOrderedAt(sheet.orderedAt(), sheet.orderId()),
                sheet.ordererName(),
                sheet.totalPaidAmount(),
                lines));
    }

    /**
     * 주문 시각을 파싱한다.
     *
     * <p>쿠팡은 타임존 없는 KST 문자열을 준다. {@code Instant.parse} 로 처리하면 UTC 로
     * 해석되어 <b>9시간 어긋난다.</b>
     */
    private static Instant parseOrderedAt(String orderedAt, long orderId) {
        try {
            return LocalDateTime.parse(orderedAt, COUPANG_DATE_TIME)
                    .atZone(COUPANG_ZONE)
                    .toInstant();
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.CHANNEL_API_FAILED, exception,
                    "쿠팡 주문 %d 의 주문시각 형식이 예상과 다릅니다: %s".formatted(orderId, orderedAt));
        }
    }

    private static String require(Map<String, String> credentials, String key) {
        String value = credentials.get(key);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.CHANNEL_CREDENTIAL_NOT_FOUND,
                    ChannelCode.COUPANG, key);
        }
        return value;
    }

    /**
     * 요청 간 간격을 둔다.
     *
     * <p>인터럽트를 삼키지 않는다. 애플리케이션 종료 신호가 오면 수집을 즉시 중단해야 한다 —
     * 커서를 전진시키지 않았으므로 다음 기동에서 같은 구간을 다시 조회한다.
     */
    private void sleepBetweenRequests() {
        try {
            Thread.sleep(coupangProperties.minRequestInterval());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.CHANNEL_API_FAILED, exception, "쿠팡");
        }
    }
}
