package kr.suhsaechan.palim.channel.adapter.coupang;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 쿠팡 서명 규격 검증.
 *
 * <p>서명이 틀리면 401 이 돌아오고, 그것이 반복되면 계정이 영구 차단될 수 있다. 실제 API 없이
 * 검증할 수 있는 부분이므로 여기서 규격을 못박는다.
 */
class CoupangSignerTest {

    private static final String SECRET_KEY = "test-secret-key";
    private static final String ACCESS_KEY = "test-access-key";

    /**
     * signedDate 는 UTC 기준이어야 한다.
     *
     * <p>서버 로컬 시각으로 만들면 KST 환경에서 9시간 어긋나 서명이 통째로 무효가 된다.
     */
    @Test
    @DisplayName("signedDate 는 UTC 기준으로 만든다")
    void signedDate는_UTC다() {
        // 2026-07-29T14:32:11Z (UTC). KST 로 해석하면 23:32 가 되어 다른 값이 나온다.
        Instant instant = Instant.parse("2026-07-29T14:32:11Z");

        assertThat(CoupangSigner.signedDate(instant)).isEqualTo("260729T143211Z");
    }

    @Test
    @DisplayName("같은 입력에는 같은 서명이 나온다")
    void 서명이_결정적이다() {
        String first = CoupangSigner.sign("GET", "/v2/path", "a=1&b=2", "260729T143211Z", SECRET_KEY);
        String second = CoupangSigner.sign("GET", "/v2/path", "a=1&b=2", "260729T143211Z", SECRET_KEY);

        assertThat(first).isEqualTo(second).hasSize(64);   // SHA-256 hex
    }

    /**
     * 서명 대상은 {@code signedDate + method + path + query} 를 구분자 없이 이어 붙인 문자열이다.
     * 어느 하나라도 달라지면 서명이 달라져야 한다.
     */
    @Test
    @DisplayName("서명 대상 요소가 달라지면 서명도 달라진다")
    void 입력이_다르면_서명이_다르다() {
        String base = CoupangSigner.sign("GET", "/v2/path", "a=1", "260729T143211Z", SECRET_KEY);

        assertThat(CoupangSigner.sign("POST", "/v2/path", "a=1", "260729T143211Z", SECRET_KEY))
                .as("method").isNotEqualTo(base);
        assertThat(CoupangSigner.sign("GET", "/v2/other", "a=1", "260729T143211Z", SECRET_KEY))
                .as("path").isNotEqualTo(base);
        assertThat(CoupangSigner.sign("GET", "/v2/path", "a=2", "260729T143211Z", SECRET_KEY))
                .as("query").isNotEqualTo(base);
        assertThat(CoupangSigner.sign("GET", "/v2/path", "a=1", "260729T143212Z", SECRET_KEY))
                .as("signedDate").isNotEqualTo(base);
        assertThat(CoupangSigner.sign("GET", "/v2/path", "a=1", "260729T143211Z", "other-secret"))
                .as("secretKey").isNotEqualTo(base);
    }

    /**
     * query 가 null 이면 빈 문자열로 다뤄야 한다. 그대로 이어 붙이면 문자열 "null" 이 서명에
     * 들어가 실패한다.
     */
    @Test
    @DisplayName("query 가 null 이면 빈 문자열과 같게 처리한다")
    void null_query는_빈문자열이다() {
        String withNull = CoupangSigner.sign("GET", "/v2/path", null, "260729T143211Z", SECRET_KEY);
        String withEmpty = CoupangSigner.sign("GET", "/v2/path", "", "260729T143211Z", SECRET_KEY);

        assertThat(withNull).isEqualTo(withEmpty);
    }

    @Test
    @DisplayName("메서드는 대문자로 정규화된다")
    void 메서드를_대문자로_정규화한다() {
        String lower = CoupangSigner.sign("get", "/v2/path", "a=1", "260729T143211Z", SECRET_KEY);
        String upper = CoupangSigner.sign("GET", "/v2/path", "a=1", "260729T143211Z", SECRET_KEY);

        assertThat(lower).isEqualTo(upper);
    }

    @Test
    @DisplayName("Authorization 헤더가 쿠팡 형식을 따른다")
    void 헤더_형식이_맞다() {
        String header = CoupangSigner.authorizationHeader(
                "GET", "/v2/path", "a=1", "260729T143211Z", ACCESS_KEY, SECRET_KEY);

        assertThat(header)
                .startsWith("CEA algorithm=HmacSHA256, access-key=" + ACCESS_KEY)
                .contains("signed-date=260729T143211Z")
                .contains("signature=");
    }
}
