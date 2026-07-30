package kr.suhsaechan.palim.web.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 비밀값 마스킹 검증.
 *
 * <p>감사 로그는 화면에 표시된다. 채널 API 키가 스냅샷에 섞이면 DB 와 화면 양쪽에 평문이
 * 남으므로, 호출부 실수와 무관하게 여기서 걸러져야 한다.
 */
class AuditSnapshotsTest {

    private final AuditSnapshots auditSnapshots = new AuditSnapshots(new ObjectMapper());

    @Test
    @DisplayName("비밀값 키는 값이 마스킹된다")
    void 비밀값_마스킹() {
        String json = auditSnapshots.toJson(Map.of(
                "secretKey", "top-secret",
                "apiKey", "plain-api-key",
                "accessKey", "AKIA123",
                "telegram-chat-id", "12345"));

        assertThat(json)
                .doesNotContain("top-secret")
                .doesNotContain("plain-api-key")
                .doesNotContain("AKIA123")
                .doesNotContain("12345")
                .contains("***");
    }

    @Test
    @DisplayName("일반 키는 값이 유지된다")
    void 일반_값_유지() {
        String json = auditSnapshots.toJson(Map.of("quantity", 10, "code", "SKU-001"));

        assertThat(json).contains("10").contains("SKU-001");
    }

    @Test
    @DisplayName("null 은 스냅샷 없음이다 — 등록에는 변경 전이 없다")
    void null_입력() {
        assertThat(auditSnapshots.toJson(null)).isNull();
    }
}
