package kr.suhsaechan.palim.channel.adapter.coupang;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.channel.adapter.ChannelOrder;
import kr.suhsaechan.palim.channel.adapter.ChannelOrderLine;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 쿠팡 어댑터 검증.
 *
 * <p>실제 인증정보 없이 WireMock 으로 검증한다. 응답 샘플은
 * {@code src/test/resources/samples/coupang/} 에 보관하며, 실제 응답을 받으면 그 자리에 넣는다 —
 * <b>채널 API 사양 변경을 감지할 유일한 수단이다</b>(05-INTEGRATION).
 */
class CoupangOrderCollectorTest {

    private static final Map<String, String> CREDENTIALS = Map.of(
            "accessKey", "test-access-key",
            "secretKey", "test-secret-key",
            "vendorId", "A00123456");

    private static final Instant FROM = Instant.parse("2026-07-29T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-07-30T00:00:00Z");

    private WireMockServer wireMockServer;
    private CoupangOrderCollector collector;

    @BeforeEach
    void startServer() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();

        collector = new CoupangOrderCollector(new CoupangProperties(
                wireMockServer.baseUrl(),
                Duration.ZERO,          // 테스트에서는 대기하지 않는다
                50,
                10,
                Duration.ofSeconds(5)));
    }

    @AfterEach
    void stopServer() {
        wireMockServer.stop();
    }

    private static String sample(String fileName) {
        String path = "/samples/coupang/" + fileName;
        try (InputStream stream = CoupangOrderCollectorTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("응답 샘플이 없습니다: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("응답 샘플을 읽을 수 없습니다: " + path, exception);
        }
    }

    private void stubJson(String body) {
        wireMockServer.stubFor(get(urlPathMatching("/v2/providers/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void stubStatus(int status) {
        wireMockServer.stubFor(get(urlPathMatching("/v2/providers/.*"))
                .willReturn(aResponse().withStatus(status).withBody("{\"code\":" + status + "}")));
    }

    // ------------------------------------------------------------------
    // 응답 파싱
    // ------------------------------------------------------------------

    @Test
    @DisplayName("응답을 ChannelOrder 로 변환한다")
    void 응답을_변환한다() {
        stubJson(sample("ordersheets-page2.json"));   // nextToken 이 빈 문자열이라 1회만 호출된다

        List<ChannelOrder> orders = collector.collect(FROM, TO, CREDENTIALS);

        assertThat(orders).singleElement().satisfies(order -> {
            assertThat(order.channelCode()).isEqualTo(ChannelCode.COUPANG);
            assertThat(order.channelOrderNo()).isEqualTo("4000000003");
            assertThat(order.buyerName()).isEqualTo("박구매");
            assertThat(order.totalAmount()).isEqualTo(12_000L);
            assertThat(order.lines()).singleElement().satisfies(line -> {
                assertThat(line.channelLineNo()).isEqualTo("900000004");
                assertThat(line.channelProductNo()).isEqualTo("700000004");
                assertThat(line.channelOptionNo()).isEqualTo("800000004");
                assertThat(line.channelProductName()).isEqualTo("마지막 페이지 상품");
                assertThat(line.quantity()).isEqualTo(1);
                assertThat(line.unitPrice()).isEqualTo(12_000L);
            });
        });
    }

    /**
     * 쿠팡은 타임존 없는 KST 문자열을 준다. UTC 로 해석하면 9시간 어긋나고, 그 값이 수집 커서와
     * 중복 판정에 쓰이면 재고가 이중 차감된다.
     */
    @Test
    @DisplayName("주문 시각을 KST 로 해석해 Instant 로 변환한다")
    void 주문시각을_KST로_해석한다() {
        stubJson(sample("ordersheets-page2.json"));

        ChannelOrder order = collector.collect(FROM, TO, CREDENTIALS).getFirst();

        // 응답의 2026-07-29T16:20:45 는 KST 다. UTC 로는 07:20:45 다.
        assertThat(order.orderedAt()).isEqualTo(Instant.parse("2026-07-29T07:20:45Z"));
    }

    /**
     * 쿠팡이 필드를 추가하는 것만으로 파싱이 깨지면 안 된다. 샘플에 미래 필드를 넣어 확인한다.
     */
    @Test
    @DisplayName("응답에 모르는 필드가 있어도 파싱된다")
    void 미지의_필드를_무시한다() {
        stubJson(sample("ordersheets-page2.json"));   // unknownFutureField 포함

        assertThat(collector.collect(FROM, TO, CREDENTIALS)).isNotEmpty();
    }

    @Test
    @DisplayName("한 주문에 여러 항목이 있으면 모두 변환한다")
    void 여러_항목을_변환한다() {
        // page1 은 nextToken 이 있으나 두 번째 호출도 같은 응답을 주므로 상한에서 멈춘다.
        stubJson(sample("ordersheets-page1.json"));

        List<ChannelOrder> orders = collector.collect(FROM, TO, CREDENTIALS);

        assertThat(orders).isNotEmpty();
        assertThat(orders.stream().filter(o -> "4000000002".equals(o.channelOrderNo())).findFirst())
                .isPresent()
                .get()
                .extracting(ChannelOrder::lines, org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(2)
                .extracting(line -> ((ChannelOrderLine) line).channelOptionNo())
                .containsExactly("800000002", "800000003");
    }

    // ------------------------------------------------------------------
    // 페이징
    // ------------------------------------------------------------------

    @Test
    @DisplayName("nextToken 이 있으면 다음 페이지를 순회한다")
    void 페이징을_순회한다() {
        wireMockServer.stubFor(get(urlPathMatching("/v2/providers/.*"))
                .withQueryParam("nextToken", com.github.tomakehurst.wiremock.client.WireMock.absent())
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(sample("ordersheets-page1.json"))));
        wireMockServer.stubFor(get(urlPathMatching("/v2/providers/.*"))
                .withQueryParam("nextToken",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("TOKEN-PAGE-2"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(sample("ordersheets-page2.json"))));

        List<ChannelOrder> orders = collector.collect(FROM, TO, CREDENTIALS);

        assertThat(orders)
                .as("1페이지 2건 + 2페이지 1건")
                .hasSize(3)
                .extracting(ChannelOrder::channelOrderNo)
                .containsExactly("4000000001", "4000000002", "4000000003");
    }

    /**
     * nextToken 이 예상과 다르게 동작하면 무한 루프가 되고, 그 상태로 호출 제한을 초과하면
     * 계정이 영구 차단된다. 상한에서 멈춰야 한다.
     */
    @Test
    @DisplayName("nextToken 이 계속 반복되면 상한에서 멈춘다")
    void 페이징_상한에서_멈춘다() {
        CoupangOrderCollector limited = new CoupangOrderCollector(new CoupangProperties(
                wireMockServer.baseUrl(), Duration.ZERO, 50, 3, Duration.ofSeconds(5)));
        stubJson(sample("ordersheets-page1.json"));   // 항상 nextToken 을 준다

        List<ChannelOrder> orders = limited.collect(FROM, TO, CREDENTIALS);

        assertThat(orders).as("3페이지 x 2건").hasSize(6);
    }

    // ------------------------------------------------------------------
    // 실패 처리 — 빈 목록을 반환해서는 안 된다
    // ------------------------------------------------------------------

    /**
     * 이 검증이 가장 중요하다. 인증 실패를 빈 목록으로 반환하면 조율 계층이 "주문이 없다"로
     * 오판해 커서를 전진시키고, <b>그 구간의 주문이 영구 유실된다.</b>
     */
    @Test
    @DisplayName("인증 실패 시 예외를 던진다 — 빈 목록이 아니다")
    void 인증_실패는_예외다() {
        stubStatus(401);

        assertThatThrownBy(() -> collector.collect(FROM, TO, CREDENTIALS))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHANNEL_API_FAILED);
    }

    @Test
    @DisplayName("호출 제한 초과 시 예외를 던진다")
    void 호출제한_초과는_예외다() {
        stubStatus(429);

        assertThatThrownBy(() -> collector.collect(FROM, TO, CREDENTIALS))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHANNEL_API_FAILED);
    }

    @Test
    @DisplayName("서버 오류 시 예외를 던진다")
    void 서버_오류는_예외다() {
        stubStatus(500);

        assertThatThrownBy(() -> collector.collect(FROM, TO, CREDENTIALS))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("인증정보가 없으면 예외를 던진다")
    void 인증정보_누락은_예외다() {
        assertThatThrownBy(() -> collector.collect(FROM, TO, Map.of("accessKey", "only-one")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHANNEL_CREDENTIAL_NOT_FOUND);
    }

    @Test
    @DisplayName("담당 채널은 쿠팡이다")
    void 담당_채널이_맞다() {
        assertThat(collector.channelCode()).isEqualTo(ChannelCode.COUPANG);
    }
}
