package kr.suhsaechan.palim.connector.source.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 로그인 폼 → 세션 쿠키 → 조회 흐름 검증.
 *
 * <p>웹 화면이 쓰는 것과 같은 경로다. 공개 API 가 유료이거나 없을 때 쓴다.
 *
 * <p><b>이 방식은 상대 화면이 바뀌면 깨진다.</b> 그래서 실패를 조용히 넘기지 않는다 — 수집이
 * 멈춘 줄 모르면 옛 데이터로 대사가 계속 돌고, 그 결과를 믿고 판단하게 된다.
 *
 * <p><b>절차를 직접 구현하지 않는다.</b> 예전에는 이 클래스가 로그인·조회·파싱을 따로 갖고
 * 있었는데, 한쪽만 고쳐지면서 <b>검증 화면은 칸을 예쁘게 보여주고 실제 적재는 덩어리를 담는</b>
 * 상태가 됐다. 화면에서 멀쩡해 보이니 사람은 알 방법이 없다. 지금은 {@link FormSessionClient}
 * 하나만 부르고, 이 클래스는 <b>어느 단계에서 막혔는지 기록하는 일</b>만 한다.
 */
@Component
@RequiredArgsConstructor
public class FormSessionProbe implements ApiProbe {

    private static final int SAMPLE_LIMIT = 5;

    private final FormSessionClient client;

    @Override
    public ApiAuthPreset.AuthFlow flow() {
        return ApiAuthPreset.AuthFlow.FORM_SESSION;
    }

    @Override
    public ProbeReport probe(ProbeRequest request) {
        List<ProbeStep> steps = new ArrayList<>();
        Map<String, String> config = request.params();
        String loginUrl = request.require("loginUrl");
        request.require("fetchUrl");
        String userId = request.require("userId");
        String password = request.requireSecret();
        String tokenField = config.getOrDefault("tokenField", "token");

        Map<String, String> cookies = new LinkedHashMap<>();

        long started = System.nanoTime();
        FormSessionClient.LoginPage page;
        try {
            page = client.openLoginPage(loginUrl, tokenField, cookies);
            // 암호화해 보내는 화면인지 여기서 갈린다. 공개키를 못 찾으면 다음 단계가 실패하므로
            // 그 사실을 이 단계에 적어 둔다 — 안 그러면 «로그인 실패» 만 보이고 이유가 없다.
            steps.add(ProbeStep.ok("로그인 화면 열기", describe(page), elapsed(started)));
        } catch (Exception e) {
            steps.add(ProbeStep.fail("로그인 화면 열기", HttpExchange.summarize(e), elapsed(started),
                    HttpExchange.statusOf(e), HttpExchange.bodyOf(e)));
            steps.add(ProbeStep.skipped("로그인"));
            steps.add(ProbeStep.skipped("데이터 조회"));
            return ProbeReport.of(List.copyOf(steps));
        }

        started = System.nanoTime();
        FormSessionClient.Session session;
        try {
            session = client.login(config, userId, password, cookies, page);
            steps.add(ProbeStep.ok("로그인", "세션 쿠키 " + cookies.size() + "개 확보",
                    elapsed(started)));
        } catch (BusinessException e) {
            steps.add(ProbeStep.fail("로그인", reasonOf(e), elapsed(started), 200, rawOf(e)));
            steps.add(ProbeStep.skipped("데이터 조회"));
            return ProbeReport.of(List.copyOf(steps));
        } catch (Exception e) {
            steps.add(ProbeStep.fail("로그인", HttpExchange.summarize(e), elapsed(started),
                    HttpExchange.statusOf(e), HttpExchange.bodyOf(e)));
            steps.add(ProbeStep.skipped("데이터 조회"));
            return ProbeReport.of(List.copyOf(steps));
        }

        started = System.nanoTime();
        try {
            List<Map<String, String>> rows = client.fetch(config, session);
            steps.add(ProbeStep.ok("데이터 조회", rows.size() + "행 확인", elapsed(started)));
            return new ProbeReport(steps, List.copyOf(rows.getFirst().keySet()),
                    rows.stream().limit(SAMPLE_LIMIT).toList(), rows.size());
        } catch (BusinessException e) {
            steps.add(ProbeStep.fail("데이터 조회", reasonOf(e), elapsed(started), 200, rawOf(e)));
            return ProbeReport.of(List.copyOf(steps));
        } catch (Exception e) {
            steps.add(ProbeStep.fail("데이터 조회", HttpExchange.summarize(e), elapsed(started),
                    HttpExchange.statusOf(e), HttpExchange.bodyOf(e)));
            return ProbeReport.of(List.copyOf(steps));
        }
    }

    /** 이 화면이 무엇을 요구하는지 한 줄로. 다음 단계가 실패했을 때 원인을 좁혀 준다. */
    private static String describe(FormSessionClient.LoginPage page) {
        if (page.hasPublicKey()) {
            return page.token().isEmpty()
                    ? "암호화 로그인 화면 (공개키 확보)"
                    : "암호화 로그인 화면 (공개키·토큰 확보)";
        }
        return page.token().isEmpty() ? "토큰 없음(불필요할 수 있음)" : "토큰 확보";
    }

    /** 예외에 담긴 사람용 사유. {@code messageArgs} 첫 값이 화면에 쓸 문장이다. */
    private static String reasonOf(BusinessException e) {
        Object[] args = e.messageArgs();
        return args.length > 0 ? String.valueOf(args[0]) : e.getErrorCode().name();
    }

    /** 상대 서버가 실제로 뭐라고 했는지. 이것이 없으면 원인을 추측으로 좁혀야 한다. */
    private static String rawOf(BusinessException e) {
        Object raw = e.getDetails().get(EcountSessionClient.RAW_RESPONSE);
        return raw == null ? null : String.valueOf(raw);
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
