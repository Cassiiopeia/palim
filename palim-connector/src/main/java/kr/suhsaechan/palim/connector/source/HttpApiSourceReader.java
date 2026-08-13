package kr.suhsaechan.palim.connector.source;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.secret.ConnectorSecretService;
import kr.suhsaechan.palim.connector.source.http.ApiAuthPreset;
import kr.suhsaechan.palim.connector.source.http.EcountSessionClient;
import kr.suhsaechan.palim.connector.source.http.FormSessionClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * REST 원천 어댑터.
 *
 * <p>이것이 없어서 <b>API 로 연결한 뒤가 통째로 끊겨 있었다.</b> 연결 확인은 통과하는데 매핑
 * 화면은 엑셀을 올리라 하고, 실행도 파일 업로드뿐이라 매일 자동으로 받아올 경로가 없었다.
 * 어댑터 하나를 채우면 매핑·시험 실행·자동 수집이 모두 이 경로로 흐른다.
 *
 * <p>{@link #readSchema} 와 {@link #read} 를 나눠 두는 이유가 여기서 드러난다 — 매핑 화면은
 * 칸 이름과 샘플 몇 행이면 되고 적재는 전체를 흘려야 한다. 하나로 합치면 화면을 열 때마다
 * 전체를 받는다.
 *
 * <p><b>비밀값은 {@link SourceContext} 에 담지 않는다.</b> 그 설정은 커넥터 정의에 그대로 남아
 * 화면에서 조회되므로, 인증키가 섞이면 목록 화면 한 번에 유출된다. 커넥터의 {@code credentialRef}
 * 로 그때그때 꺼낸다.
 */
@Component
@RequiredArgsConstructor
public class HttpApiSourceReader implements SourceReader {

    private static final int SAMPLE_LIMIT = 5;

    private final EcountSessionClient ecount;
    private final FormSessionClient form;
    private final ConnectorRepository connectorRepository;
    private final ConnectorSecretService secrets;

    @Override
    public SourceType type() {
        return SourceType.HTTP_API;
    }

    @Override
    public SourceSchema readSchema(SourceContext context) {
        List<Map<String, String>> rows = fetch(context);
        // 칸 이름은 첫 행에서 얻는다. 문서에 적힌 이름과 다른 일이 잦아 실제로 받은 것을 기준으로 한다.
        List<String> fields = rows.isEmpty() ? List.of() : List.copyOf(rows.getFirst().keySet());
        List<Map<String, Object>> samples = rows.stream()
                .limit(SAMPLE_LIMIT)
                .map(row -> (Map<String, Object>) new LinkedHashMap<String, Object>(row))
                .toList();
        return new SourceSchema(fields, samples, rows.size());
    }

    @Override
    public Stream<SourceRow> read(SourceContext context) {
        List<Map<String, String>> rows = fetch(context);
        // 줄 번호는 1부터. 실패한 줄을 사람에게 알릴 때 «2번째 줄» 이라고 말할 수 있어야 한다.
        return IntStream.range(0, rows.size())
                .mapToObj(index -> new SourceRow(index + 1, new LinkedHashMap<>(rows.get(index))));
    }

    /**
     * 원천에서 행을 받아온다.
     *
     * <p>프리셋마다 인증 절차가 다르므로 여기서 갈라진다. 새 절차가 붙어도 이 메서드에 분기 하나가
     * 늘 뿐, 매핑·적재·스케줄은 그대로다.
     */
    private List<Map<String, String>> fetch(SourceContext context) {
        Connector connector = connectorRepository.findById(context.connectorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTOR_NOT_FOUND,
                        context.connectorId()));

        Map<String, Object> config = context.config() == null || context.config().isEmpty()
                ? connector.getSourceConfig()
                : context.config();

        ApiAuthPreset preset = presetOf(config);
        String secret = secretOf(connector, preset);

        return switch (preset.getFlow()) {
            case ZONE_SESSION -> fetchViaSession(config, preset, secret);
            case FORM_SESSION -> fetchViaForm(config, preset, secret);
        };
    }

    /**
     * 로그인 화면을 통과해 받아온다.
     *
     * <p>공개 API 가 없거나 유료인 시스템이 쓰는 경로다. <b>상대 화면이 바뀌면 깨지므로</b>
     * 실패를 조용히 삼키지 않는다 — 옛 자료로 대조가 계속 돌면 그 결과를 믿고 판단하게 된다.
     */
    private List<Map<String, String>> fetchViaForm(Map<String, Object> config,
                                                   ApiAuthPreset preset, String secret) {
        Map<String, String> merged = preset.mergeDefaults(asText(config));
        String userId = required(merged, "userId");

        Map<String, String> cookies = new LinkedHashMap<>();
        String token = form.openLoginPage(required(merged, "loginUrl"),
                merged.getOrDefault("tokenField", "token"), cookies);
        FormSessionClient.Session session = form.login(merged, userId, secret, cookies, token);
        return form.fetch(merged, session);
    }

    private List<Map<String, String>> fetchViaSession(Map<String, Object> config,
                                                      ApiAuthPreset preset, String secret) {
        Map<String, String> merged = preset.mergeDefaults(asText(config));

        var endpoint = new EcountSessionClient.EcountEndpoint(
                merged.get("apiDomain"), merged.get("sandboxPrefix"), merged.get("livePrefix"),
                Boolean.parseBoolean(String.valueOf(config.getOrDefault("sandbox", "false"))),
                merged.get("zoneUrl"), merged.get("apiBase"));

        String companyCode = required(merged, "companyCode");
        String userId = required(merged, "userId");

        String zone = ecount.resolveZone(endpoint, companyCode);
        String session = ecount.login(endpoint, zone, companyCode, userId, secret);
        return ecount.fetchInventory(endpoint, zone, session, baseDate(merged));
    }

    /**
     * 인증키·비밀번호.
     *
     * <p>이름표가 흐름마다 다르다. 나중에 사람이 «무엇을 넣었는지» 알아볼 수 있어야 하기 때문이며,
     * 저장할 때 쓴 이름과 같아야 찾을 수 있다.
     */
    private String secretOf(Connector connector, ApiAuthPreset preset) {
        String name = switch (preset.getFlow()) {
            case ZONE_SESSION -> "apiKey";
            case FORM_SESSION -> "password";
        };
        return secrets.find(connector.getCredentialRef(), name)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTOR_SOURCE_UNREACHABLE,
                        "저장된 인증정보가 없습니다. 연결을 다시 확인하세요."));
    }

    private static ApiAuthPreset presetOf(Map<String, Object> config) {
        String name = String.valueOf(config.getOrDefault("preset", ApiAuthPreset.ECOUNT.name()));
        try {
            return ApiAuthPreset.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.CONNECTOR_SOURCE_UNREACHABLE, name);
        }
    }

    /** 기준일이 없으면 오늘로 본다. 매일 도는 수집은 «오늘 재고» 를 받는 것이 정상이다. */
    private static LocalDate baseDate(Map<String, String> config) {
        String raw = config.get("baseDate");
        return raw == null || raw.isBlank() ? LocalDate.now() : LocalDate.parse(raw.trim());
    }

    private static Map<String, String> asText(Map<String, Object> config) {
        Map<String, String> text = new LinkedHashMap<>();
        config.forEach((key, value) -> text.put(key, Objects.toString(value, "")));
        return text;
    }

    private static String required(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.API_PROBE_INCOMPLETE, key);
        }
        return value;
    }
}
