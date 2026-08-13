package kr.suhsaechan.palim.web.connector;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.suhsaechan.palim.connector.source.http.ApiAuthPreset;
import kr.suhsaechan.palim.connector.source.http.ProbeRequest;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

/**
 * 연결 정보 입력.
 *
 * <p>비밀값({@link #secret})만 따로 담는다. 나머지 값은 화면에 다시 표시해도 되지만 이것은
 * 안 되기 때문이다. 한 덩어리로 다루면 어느 화면에선가 통째로 출력된다.
 *
 * <p>프리셋마다 필요한 항목이 다르다. 필드를 프리셋 수만큼 늘리는 대신 <b>공통 항목 + 프리셋별
 * 항목</b>으로 나눠, 새 인증 흐름이 붙어도 이 클래스를 고치지 않게 한다.
 */
@Getter
@Setter
public class ConnectionForm {

    /** 인증 흐름. */
    private ApiAuthPreset preset = ApiAuthPreset.ZONE_SESSION;

    /** 테스트 환경 여부. 테스트키와 정식키는 접속 주소가 다르다. */
    private boolean sandbox = true;

    // ── 공통 ──
    private String userId;
    private String secret;

    // ── ZONE_SESSION ──
    private String companyCode;
    private String baseDate;

    // ── FORM_SESSION ──
    private String loginUrl;
    private String fetchUrl;
    private String fetchBody;
    private String rowsPath = "rows";
    private String domain;
    private String useridField = "userid";
    private String passwordField = "passwd";
    private String tokenField = "token";

    /** 커넥터로 저장할 때 쓸 이름·코드. */
    private String name;
    private String code;
    private String targetModelCode = "std_stock_snapshot";
    private String defaultUnit = "EA";

    public ProbeRequest toProbeRequest() {
        Map<String, String> params = new LinkedHashMap<>();
        putIfPresent(params, "userId", userId);
        if (preset == ApiAuthPreset.ZONE_SESSION) {
            putIfPresent(params, "companyCode", companyCode);
            putIfPresent(params, "baseDate", baseDate);
        } else {
            putIfPresent(params, "loginUrl", loginUrl);
            putIfPresent(params, "fetchUrl", fetchUrl);
            putIfPresent(params, "fetchBody", fetchBody);
            putIfPresent(params, "rowsPath", rowsPath);
            putIfPresent(params, "domain", domain);
            putIfPresent(params, "useridField", useridField);
            putIfPresent(params, "passwordField", passwordField);
            putIfPresent(params, "tokenField", tokenField);
        }
        return new ProbeRequest(preset, sandbox, params, secret);
    }

    /**
     * 커넥터에 저장할 설정.
     *
     * <p><b>비밀값은 들어가지 않는다.</b> 이 값은 커넥터 정의에 그대로 남아 화면에서 조회되므로,
     * 여기 인증키가 섞이면 목록 화면 한 번에 유출된다.
     */
    public Map<String, Object> toSourceConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("preset", preset.name());
        config.put("sandbox", sandbox);
        toProbeRequest().params().forEach(config::put);
        return config;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }
}
