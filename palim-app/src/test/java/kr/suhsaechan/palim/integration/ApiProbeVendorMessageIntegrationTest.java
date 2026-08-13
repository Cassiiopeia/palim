package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.source.http.ApiAuthPreset;
import kr.suhsaechan.palim.connector.source.http.ApiProbeRegistry;
import kr.suhsaechan.palim.connector.source.http.ProbeReport;
import kr.suhsaechan.palim.connector.source.http.ProbeRequest;
import kr.suhsaechan.palim.connector.source.http.ProbeStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 거부 사유를 <b>상대가 보낸 말 그대로</b> 화면에 옮기는가.
 *
 * <p>이 테스트가 있는 이유가 있다. 로그인이 세션을 주지 않았을 때 "인증키가 이 사용자 ID 로
 * 발급된 것인지 확인하세요"라는 <b>우리가 짐작한 문장</b>을 띄운 적이 있다. 상대가 보낸 진짜
 * 사유는 <b>서버 IP 가 허용 목록에 없다</b>였고, 키는 멀쩡했다.
 *
 * <p>그 화면만 보면 키를 의심하게 된다. 키를 다시 발급받아도 증상은 같고, 원인은 끝내 나오지
 * 않는다. 짐작한 문장이 상대의 답을 가려 버렸기 때문이다.
 */
class ApiProbeVendorMessageIntegrationTest extends IntegrationTest {

    /**
     * 상대가 접속을 거부하며 <b>자기가 본 요청 주소</b>를 적어 보낸 응답.
     *
     * <p>주소는 문서용으로 예약된 대역(RFC 5737)을 쓴다. 실제 운영 서버 주소를 테스트에 적으면
     * 그 값이 저장소에 영구히 남는다.
     */
    private static final String REJECTED_IP = "203.0.113.10";
    private static final String IP_REJECTED = """
            {"Data":{"Code":"205","Datas":{},
             "Message":"[%s] 허용되지 않은 IP입니다. IP등록을 진행하시기 바랍니다."}}"""
            .formatted(REJECTED_IP);

    @Autowired private ApiProbeRegistry probes;

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // 지역 조회는 통과시키고 로그인에서 거부한다 — 실제로 막혔던 지점이 그곳이다.
            String path = exchange.getRequestURI().getPath();
            String json = path.endsWith("/zone")
                    ? "{\"Data\":{\"ZONE\":\"AD\"}}"
                    : IP_REJECTED;
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            // 거부인데도 200 으로 돌아온다. 상태 코드만 보면 성공으로 읽힌다.
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("로그인이 거부되면 상대가 보낸 사유와 등록할 주소를 그대로 알려준다")
    void 거부_사유를_짐작으로_덮지_않는다() {
        String local = "http://127.0.0.1:" + server.getAddress().getPort();
        ProbeRequest request = new ProbeRequest(ApiAuthPreset.ECOUNT, true,
                Map.of("companyCode", "123456",
                        "userId", "tester",
                        "zoneUrl", local + "/zone",
                        "apiBase", local),
                "dummy-key");

        ProbeReport report = probes.of(ApiAuthPreset.ECOUNT).probe(request);

        ProbeStep login = report.steps().stream()
                .filter(step -> step.name().equals("로그인"))
                .findFirst()
                .orElseThrow();

        assertThat(login.success()).isFalse();
        assertThat(login.message())
                .as("상대가 사유를 보냈는데 우리 짐작으로 덮으면 엉뚱한 곳을 고치게 된다")
                .contains("허용되지 않은 IP")
                .doesNotContain("인증키가 이 사용자 ID");
        assertThat(login.message())
                .as("등록할 주소는 상대가 응답에 적어 보낸다. 그 값을 그대로 짚어 줘야 한다")
                .contains(REJECTED_IP);
        assertThat(login.hasRawResponse())
                .as("요약을 못 믿을 때 응답 원문을 볼 수 있어야 한다")
                .isTrue();
    }
}
