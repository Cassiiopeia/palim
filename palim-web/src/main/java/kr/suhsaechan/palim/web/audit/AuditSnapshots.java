package kr.suhsaechan.palim.web.audit;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 감사 로그의 변경 전·후 스냅샷을 JSON 으로 만든다.
 *
 * <h2>비밀값은 스냅샷 단계에서 지운다</h2>
 *
 * <p>감사 로그는 화면에 그대로 표시된다. 채널 API 키가 스냅샷에 섞여 들어가면
 * {@code ChannelCredentialService} 경계 밖으로 인증정보가 나가는 것이고
 * (CLAUDE.md 금지사항 7), <b>DB 와 화면 양쪽에 평문이 남는다.</b>
 *
 * <p>호출부가 조심하는 것만으로는 부족하다. 새 설정 항목이 추가될 때 그 호출부가 이 규칙을
 * 기억하지 못하면 그대로 새어나가므로, 여기서 키 이름 기준으로 한 번 더 지운다. 이중 방어다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditSnapshots {

    /** 값이 통째로 마스킹되는 키 이름 조각. 소문자로 비교한다. */
    private static final Set<String> SECRET_KEY_PARTS = Set.of(
            "password", "passwd", "secret", "token", "credential",
            "apikey", "api_key", "accesskey", "access_key", "privatekey", "private_key",
            "signature", "chatid", "chat_id", "authorization", "cookie");

    private static final String MASKED = "***";

    private final ObjectMapper objectMapper;

    /**
     * 값 묶음을 JSON 문자열로 만든다.
     *
     * <p>{@code null} 을 넘기면 {@code null} 을 반환한다 — 등록처럼 "변경 전" 이 없는 작업이 있다.
     */
    public String toJson(Map<String, ?> values) {
        if (values == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(mask(values));
        } catch (JacksonException exception) {
            // 직렬화 실패로 감사 기록을 통째로 잃지 않는다. 스냅샷만 포기한다.
            log.warn("감사 스냅샷 직렬화 실패 — 키 {}", values.keySet(), exception);
            return null;
        }
    }

    private Map<String, Object> mask(Map<String, ?> values) {
        Map<String, Object> masked = new LinkedHashMap<>();
        values.forEach((key, value) -> masked.put(key, isSecret(key) ? MASKED : value));
        return masked;
    }

    private boolean isSecret(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
        return SECRET_KEY_PARTS.stream().anyMatch(normalized::contains);
    }
}
