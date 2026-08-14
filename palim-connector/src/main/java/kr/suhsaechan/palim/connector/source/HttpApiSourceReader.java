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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
        log.debug("스키마 조회 시작 — 커넥터={}, 헤더행={}, 요청설정키={}", context.connectorId(),
                context.headerRow(), context.config() == null ? null : context.config().keySet());
        List<Map<String, String>> rows = fetch(context);
        // 칸 이름은 첫 행에서 얻는다. 문서에 적힌 이름과 다른 일이 잦아 실제로 받은 것을 기준으로 한다.
        List<String> fields = rows.isEmpty() ? List.of() : List.copyOf(rows.getFirst().keySet());
        List<Map<String, Object>> samples = rows.stream()
                .limit(SAMPLE_LIMIT)
                .map(row -> (Map<String, Object>) new LinkedHashMap<String, Object>(row))
                .toList();
        if (fields.isEmpty()) {
            // 칸이 없으면 매핑 화면이 통째로 비어 보인다 — 원인이 «응답 0건» 임을 여기서 못 박아 둔다.
            log.warn("스키마에서 칸을 하나도 얻지 못했습니다 — 커넥터={} (응답 행이 0건입니다)",
                    context.connectorId());
        }
        log.debug("스키마 샘플 — 커넥터={}, 샘플={}", context.connectorId(), samples);
        log.info("스키마 조회 완료 — 커넥터={}, 칸 {}개={}, 전체 {}행, 샘플 {}행",
                context.connectorId(), fields.size(), fields, rows.size(), samples.size());
        return new SourceSchema(fields, samples, rows.size());
    }

    @Override
    public Stream<SourceRow> read(SourceContext context) {
        log.debug("원천 읽기 시작 — 커넥터={}, 커서={}, 요청설정키={}", context.connectorId(),
                context.cursor(), context.config() == null ? null : context.config().keySet());
        List<Map<String, String>> rows = fetch(context);
        log.info("원천 읽기 완료 — 커넥터={}, {}행을 적재로 넘깁니다", context.connectorId(), rows.size());
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
                .orElseThrow(() -> {
                    log.error("커넥터를 찾지 못했습니다 — 커넥터={}", context.connectorId());
                    return new BusinessException(ErrorCode.CONNECTOR_NOT_FOUND,
                            context.connectorId());
                });

        Map<String, Object> config = context.config() == null || context.config().isEmpty()
                ? connector.getSourceConfig()
                : context.config();
        log.debug("설정 확정 — 커넥터={}({}), 출처={}, 설정키={}", connector.getCode(), connector.getId(),
                context.config() == null || context.config().isEmpty() ? "커넥터정의" : "요청컨텍스트",
                config == null ? null : config.keySet());

        ApiAuthPreset preset = presetOf(config);
        String secret = secretOf(connector, preset);
        log.debug("인증 흐름 진입 — 커넥터={}, 프리셋={}({}), 흐름={}", connector.getCode(), preset,
                preset.getLabel(), preset.getFlow());

        List<Map<String, String>> rows = switch (preset.getFlow()) {
            case ZONE_SESSION -> fetchViaSession(config, preset, secret);
            case FORM_SESSION -> fetchViaForm(config, preset, secret);
        };

        if (rows.isEmpty()) {
            // 0건은 «성공했지만 아무것도 없음» 이라 조용히 지나가면 며칠 뒤에야 안다.
            log.warn("원천 응답에 행이 0건입니다 — 커넥터={}, 프리셋={}. 호출 자체는 성공했으니 "
                    + "조회 조건·기준일·계정 권한을 확인하세요", connector.getCode(), preset);
        } else {
            log.info("원천 수집 완료 — 커넥터={}, 프리셋={}, {}행", connector.getCode(), preset, rows.size());
        }
        // 상대 시스템이 바뀌면 «형식은 맞는데 값이 다른» 형태로 깨진다. 원인 추적용으로 받은 것을 그대로 남긴다.
        log.debug("원천 응답 내용 — 커넥터={}, 행={}", connector.getCode(), rows);
        return rows;
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
        log.debug("폼 로그인 수집 시작 — 프리셋={}, 계정={}, 로그인주소={}, 조회주소={}, 행경로={}",
                preset, userId, merged.get("loginUrl"), merged.get("fetchUrl"),
                merged.get("rowsPath"));

        Map<String, String> cookies = new LinkedHashMap<>();
        FormSessionClient.LoginPage page = form.openLoginPage(required(merged, "loginUrl"),
                merged.getOrDefault("tokenField", "token"), cookies);
        // 토큰·공개키 값 자체는 남기지 않는다 — 확보 여부만 알면 원인은 갈린다.
        log.debug("로그인 화면 통과 — 프리셋={}, 토큰확보={}, 공개키확보={}, 받은쿠키={}",
                preset, !page.token().isEmpty(), page.hasPublicKey(), cookies.keySet());

        FormSessionClient.Session session = form.login(merged, userId, secret, cookies, page);
        List<Map<String, String>> rows = form.fetch(merged, session);
        log.debug("폼 조회 응답 — 프리셋={}, 조회주소={}, {}행", preset, merged.get("fetchUrl"), rows.size());
        return rows;
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
        log.debug("지역조회 방식 수집 시작 — 프리셋={}, 회사코드={}, 계정={}, 도메인={}, 샌드박스={}",
                preset, companyCode, userId, merged.get("apiDomain"),
                config.getOrDefault("sandbox", "false"));

        String zone = ecount.resolveZone(endpoint, companyCode);
        log.debug("지역 조회 완료 — 회사코드={}, 지역={}", companyCode, zone);

        String session = ecount.login(endpoint, zone, companyCode, userId, secret);
        // 세션 ID 는 값 대신 확보 여부만 남긴다 — 로그 파일에 영구히 남을 이유가 없다.
        log.debug("세션 로그인 완료 — 회사코드={}, 지역={}, 세션확보={}",
                companyCode, zone, session != null && !session.isBlank());

        // 기준일 계산은 원래 순서대로 조회 직전에 둔다 — 형식 오류가 로그인보다 먼저 터지지 않게.
        LocalDate baseDate = baseDate(merged);
        List<Map<String, String>> rows = ecount.fetchInventory(endpoint, zone, session, baseDate);
        log.debug("재고 조회 응답 — 회사코드={}, 지역={}, 기준일={}, {}행",
                companyCode, zone, baseDate, rows.size());
        return rows;
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
        log.debug("인증정보 조회 — 커넥터={}, 참조={}, 이름={}", connector.getCode(),
                connector.getCredentialRef(), name);
        return secrets.find(connector.getCredentialRef(), name)
                .orElseThrow(() -> {
                    log.error("저장된 인증정보가 없습니다 — 커넥터={}, 참조={}, 이름={}",
                            connector.getCode(), connector.getCredentialRef(), name);
                    return new BusinessException(ErrorCode.CONNECTOR_SOURCE_UNREACHABLE,
                            "저장된 인증정보가 없습니다. 연결을 다시 확인하세요.");
                });
    }

    private static ApiAuthPreset presetOf(Map<String, Object> config) {
        String name = String.valueOf(config.getOrDefault("preset", ApiAuthPreset.ECOUNT.name()));
        try {
            return ApiAuthPreset.valueOf(name);
        } catch (IllegalArgumentException e) {
            log.error("알 수 없는 프리셋입니다 — 값={}, 사용가능={}", name,
                    List.of(ApiAuthPreset.values()), e);
            throw new BusinessException(ErrorCode.CONNECTOR_SOURCE_UNREACHABLE, name);
        }
    }

    /** 기준일이 없으면 오늘로 본다. 매일 도는 수집은 «오늘 재고» 를 받는 것이 정상이다. */
    private static LocalDate baseDate(Map<String, String> config) {
        String raw = config.get("baseDate");
        if (raw == null || raw.isBlank()) {
            LocalDate today = LocalDate.now();
            log.debug("기준일 설정이 없어 오늘로 수집합니다 — 기준일={}", today);
            return today;
        }
        return LocalDate.parse(raw.trim());
    }

    private static Map<String, String> asText(Map<String, Object> config) {
        Map<String, String> text = new LinkedHashMap<>();
        config.forEach((key, value) -> text.put(key, Objects.toString(value, "")));
        return text;
    }

    private static String required(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            log.error("필수 설정값이 비어 있습니다 — 항목={}, 가진설정키={}", key, config.keySet());
            throw new BusinessException(ErrorCode.API_PROBE_INCOMPLETE, key);
        }
        return value;
    }
}
