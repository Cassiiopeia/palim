package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.secret.ConnectorSecretService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 연동 인증정보 저장.
 *
 * <p>확인하려는 것은 하나다 — <b>평문이 DB 에 남지 않는가.</b> 서비스가 평문을 돌려주는 것과
 * DB 에 평문이 있는 것은 전혀 다른 문제인데, 서비스만 테스트하면 둘을 구분하지 못한다.
 */
class ConnectorSecretIntegrationTest extends IntegrationTest {

    @Autowired private ConnectorSecretService secretService;
    @Autowired private JdbcClient jdbcClient;

    @Test
    @DisplayName("저장한 인증정보를 평문으로 되돌려준다")
    void 저장하고_읽는다() {
        String ref = "test:" + UUID.randomUUID();
        secretService.put(ref, "apiKey", "SECRET-VALUE-1234");

        assertThat(secretService.find(ref, "apiKey")).contains("SECRET-VALUE-1234");
    }

    @Test
    @DisplayName("DB 에는 평문이 남지 않는다")
    void 평문이_저장되지_않는다() {
        String ref = "test:" + UUID.randomUUID();
        String plain = "PLAINTEXT-" + UUID.randomUUID();
        secretService.put(ref, "apiKey", plain);

        String stored = jdbcClient.sql("""
                        SELECT encrypted_value FROM connector_secret
                        WHERE credential_ref = :ref AND secret_name = 'apiKey'
                        """)
                .param("ref", ref)
                .query(String.class).single();

        assertThat(stored)
                .as("DB 가 유출되면 그대로 읽힌다. 암호문이어야 한다")
                .doesNotContain(plain);
        assertThat(stored).isNotBlank();
    }

    @Test
    @DisplayName("같은 이름으로 다시 저장하면 갱신된다")
    void 같은_이름은_갱신한다() {
        String ref = "test:" + UUID.randomUUID();
        secretService.put(ref, "apiKey", "OLD");
        secretService.put(ref, "apiKey", "NEW");

        assertThat(secretService.find(ref, "apiKey")).contains("NEW");

        int count = jdbcClient.sql("""
                        SELECT count(*)::int FROM connector_secret
                        WHERE credential_ref = :ref AND secret_name = 'apiKey'
                        """)
                .param("ref", ref)
                .query(Integer.class).single();
        assertThat(count).as("두 벌이 남으면 어느 것이 쓰이는지 알 수 없다").isEqualTo(1);
    }

    @Test
    @DisplayName("등록 목록은 이름만 돌려준다")
    void 값은_목록에_나오지_않는다() {
        String ref = "test:" + UUID.randomUUID();
        secretService.put(ref, "apiKey", "SECRET-A");
        secretService.put(ref, "password", "SECRET-B");

        assertThat(secretService.keysOf(ref))
                .as("화면은 이 목록을 그대로 그린다. 값이 섞이면 그 화면이 유출 경로가 된다")
                .containsExactlyInAnyOrder("apiKey", "password")
                .noneMatch(key -> key.contains("SECRET"));
    }

    @Test
    @DisplayName("빈 값은 저장하지 않는다")
    void 빈_값을_막는다() {
        String ref = "test:" + UUID.randomUUID();

        assertThatThrownBy(() -> secretService.put(ref, "apiKey", "  "))
                .as("빈 값이 저장되면 화면에는 등록된 것으로 보이면서 실행만 실패한다")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("연동을 지우면 인증정보도 함께 지운다")
    void 함께_삭제된다() {
        String ref = "test:" + UUID.randomUUID();
        secretService.put(ref, "apiKey", "X");
        secretService.deleteAll(ref);

        assertThat(secretService.keysOf(ref))
                .as("남겨두면 어디서도 참조하지 않는 비밀값이 쌓인다").isEmpty();
    }
}
