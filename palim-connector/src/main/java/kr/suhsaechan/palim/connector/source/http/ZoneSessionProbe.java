package kr.suhsaechan.palim.connector.source.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.suhsaechan.palim.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 지역 조회 → 로그인 → 조회 3단계 인증 흐름 검증.
 *
 * <p>절차가 셋으로 나뉜 시스템은 각 단계가 서로 다른 이유로 실패한다. 회사코드가 틀리면 1단계,
 * 인증키가 만료됐거나 허용 ID 가 다르면 2단계, 조회 권한이 없으면 3단계에서 막힌다. 그래서
 * <b>단계를 합치지 않고 각각 기록</b>한다.
 *
 * <p>성공했을 때 <b>응답 필드와 샘플까지 함께</b> 돌려준다. 매핑을 짜려면 상대가 실제로 보내는
 * 칸 이름이 필요한데, 그것을 알아내려고 한 번 더 호출하게 만들 이유가 없다 — 방금 받아 온
 * 응답에 이미 들어 있다.
 *
 * <p>인증 절차 자체는 {@link EcountSessionClient} 에 있다. 이 클래스가 하는 일은 <b>그 절차를
 * 단계별로 사람에게 보여주는 것</b>이다. 둘을 합쳐 두면 실제 수집 경로가 리포트 조립까지
 * 끌고 가게 되고, 나눠서 두 벌 짜면 한쪽만 고쳐져 어긋난다.
 */
@Component
@RequiredArgsConstructor
public class ZoneSessionProbe implements ApiProbe {

    private static final int SAMPLE_LIMIT = 5;

    /** 거부 메시지에 박혀 오는 요청 IP. 등록해야 할 주소가 곧 이 값이다. */
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3})");

    /**
     * 접속 주소 기본값.
     *
     * <p>테스트키와 정식키는 접속 주소가 다르다. 섞어 쓰면 인증이 통과하지 않는다.
     *
     * <p>여기 값은 <b>기본값일 뿐</b>이고 화면에서 바꿀 수 있다. 특정 벤더 주소를 코드에 고정하면
     * 지역이 다른 같은 제품이나 다른 시스템에 붙일 때 코드를 고쳐야 한다.
     */
    private static final String DEFAULT_SANDBOX_PREFIX = "sboapi";
    private static final String DEFAULT_LIVE_PREFIX = "oapi";
    private static final String DEFAULT_DOMAIN = "ecount.com";

    private final EcountSessionClient client;

    @Override
    public ApiAuthPreset.AuthFlow flow() {
        return ApiAuthPreset.AuthFlow.ZONE_SESSION;
    }

    @Override
    public ProbeReport probe(ProbeRequest request) {
        List<ProbeStep> steps = new ArrayList<>();
        String companyCode = request.require("companyCode");
        String userId = request.require("userId");
        String apiKey = request.requireSecret();

        String domain = request.params().getOrDefault("apiDomain", DEFAULT_DOMAIN);
        String sandboxPrefix = request.params()
                .getOrDefault("sandboxPrefix", DEFAULT_SANDBOX_PREFIX);
        String livePrefix = request.params().getOrDefault("livePrefix", DEFAULT_LIVE_PREFIX);

        // 지역 조회도 환경을 따른다. 테스트 환경에는 "테스트용으로 따로 만든 회사"만 있고
        // 실제 운영 회사는 없다. 환경을 고정하면 운영 회사를 넣어도 늘 빈 지역이 돌아온다.
        //
        // 주소를 통째로 덮어쓸 수 있게 둔다. 기본 조립이 맞지 않는 환경(지역이 다른 같은
        // 제품, 사설망 게이트웨이)에서 코드를 고치지 않고 화면에서 바꿀 수 있어야 한다.
        var endpoint = new EcountSessionClient.EcountEndpoint(domain, sandboxPrefix, livePrefix,
                request.sandbox(), request.params().get("zoneUrl"),
                request.params().get("apiBase"));

        String zone;
        long started = System.nanoTime();
        try {
            zone = client.resolveZone(endpoint, companyCode);
            steps.add(ProbeStep.ok("지역 조회", "지역 = " + zone, elapsed(started)));
        } catch (BusinessException e) {
            steps.add(ProbeStep.fail("지역 조회", zoneReason(e, request.sandbox()),
                    elapsed(started), 200, rawOf(e)));
            steps.add(ProbeStep.skipped("로그인"));
            steps.add(ProbeStep.skipped("재고 조회"));
            return finish(steps);
        } catch (Exception e) {
            steps.add(ProbeStep.fail("지역 조회", HttpExchange.summarize(e), elapsed(started),
                    HttpExchange.statusOf(e), HttpExchange.bodyOf(e)));
            steps.add(ProbeStep.skipped("로그인"));
            steps.add(ProbeStep.skipped("재고 조회"));
            return finish(steps);
        }

        String sessionId;
        started = System.nanoTime();
        try {
            sessionId = client.login(endpoint, zone, companyCode, userId, apiKey);
            steps.add(ProbeStep.ok("로그인", "세션 발급됨", elapsed(started)));
        } catch (BusinessException e) {
            steps.add(ProbeStep.fail("로그인", withIpHint(reasonOf(e)),
                    elapsed(started), 200, rawOf(e)));
            steps.add(ProbeStep.skipped("재고 조회"));
            return finish(steps);
        } catch (Exception e) {
            steps.add(ProbeStep.fail("로그인", HttpExchange.summarize(e), elapsed(started),
                    HttpExchange.statusOf(e), HttpExchange.bodyOf(e)));
            steps.add(ProbeStep.skipped("재고 조회"));
            return finish(steps);
        }

        started = System.nanoTime();
        try {
            List<Map<String, String>> samples =
                    client.fetchInventory(endpoint, zone, sessionId, request.baseDate());
            if (samples.isEmpty()) {
                // 연결은 됐는데 자료가 없는 것과 거부당한 것은 다르다. 거부는 클라이언트가
                // 예외로 알려주므로, 여기까지 왔다는 것은 정말 없다는 뜻이다.
                steps.add(ProbeStep.ok("재고 조회",
                        "호출은 성공했지만 해당 기준일에 재고가 없습니다. 기준일을 바꿔 보세요.",
                        elapsed(started)));
                return finish(steps);
            }
            steps.add(ProbeStep.ok("재고 조회", samples.size() + "행 이상 확인", elapsed(started)));
            return new ProbeReport(steps, List.copyOf(samples.getFirst().keySet()),
                    samples.stream().limit(SAMPLE_LIMIT).toList(), -1);
        } catch (BusinessException e) {
            steps.add(ProbeStep.fail("재고 조회", withIpHint(reasonOf(e)),
                    elapsed(started), 200, rawOf(e)));
            return finish(steps);
        } catch (Exception e) {
            steps.add(ProbeStep.fail("재고 조회", HttpExchange.summarize(e), elapsed(started),
                    HttpExchange.statusOf(e), HttpExchange.bodyOf(e)));
            return finish(steps);
        }
    }

    /** 실패 사유. 클라이언트가 상대 메시지를 그대로 실어 보낸다. */
    private static String reasonOf(BusinessException e) {
        Object[] args = e.messageArgs();
        return args.length > 0 ? String.valueOf(args[0]) : "요청이 거부됐습니다.";
    }

    /** 응답 원문. 요약을 못 믿을 때 사람이 직접 봐야 한다. */
    private static String rawOf(BusinessException e) {
        return String.valueOf(
                e.getDetails().getOrDefault(EcountSessionClient.RAW_RESPONSE, ""));
    }

    /**
     * 지역 조회 실패 안내.
     *
     * <p>상대가 사유를 보냈으면 그것을 쓴다. 사유 없이 {@code EMPTY_ZONE} 신호만 오는 경우가
     * 있는데, 그때는 대개 <b>환경이 어긋난 것</b>이다 — 테스트 환경에는 테스트용으로 따로 만든
     * 회사만 있고 실제 운영 회사는 없다. 이 구분이 없으면 사용자는 회사코드가 틀린 줄 알고
     * 맞는 값을 계속 다시 넣는다.
     */
    private static String zoneReason(BusinessException e, boolean sandbox) {
        String reason = reasonOf(e);
        if (!rawOf(e).contains("EMPTY_ZONE")) {
            return withIpHint(reason);
        }
        return sandbox
                ? "이 회사코드가 테스트 환경에 없습니다. 실제 운영 회사라면 위의 "
                  + "'테스트 환경으로 접속'을 끄고 다시 시도하세요."
                : "이 회사코드에 해당하는 지역이 없습니다. 회사코드를 확인하세요.";
    }

    /**
     * 실패 사유에 <b>등록할 주소</b>를 덧붙인다.
     *
     * <p>접속 IP 를 제한하는 시스템은 거부 메시지에 자기가 본 요청 IP 를 적어 보낸다. 그 값이
     * 곧 등록해야 할 주소다. 서버가 자기 공인 IP 를 스스로 알아내려면 외부 서비스를 불러야 하고,
     * NAT·프록시를 거치면 그렇게 얻은 값이 상대가 실제로 본 값과 다를 수 있다. 상대가 알려준
     * 값을 그대로 옮기는 편이 언제나 정확하다.
     */
    private static String withIpHint(String reason) {
        if (!reason.contains("IP")) {
            return reason;
        }
        Matcher found = IPV4.matcher(reason);
        return found.find()
                ? reason + " → 이 서버의 주소 %s 를 상대 시스템의 허용 IP 목록에 등록한 뒤 다시 시도하세요."
                        .formatted(found.group(1))
                : reason;
    }


    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static ProbeReport finish(List<ProbeStep> steps) {
        return ProbeReport.of(List.copyOf(steps));
    }
}
