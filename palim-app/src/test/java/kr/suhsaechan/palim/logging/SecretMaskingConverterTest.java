package kr.suhsaechan.palim.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretMaskingConverterTest {

    @Test
    @DisplayName("비밀 이름의 키=값은 마스킹된다")
    void 키값_마스킹() {
        assertThat(SecretMaskingConverter.mask("요청 실패 password=hunter2 재시도"))
                .isEqualTo("요청 실패 password=*** 재시도");
        assertThat(SecretMaskingConverter.mask("apiKey: AKIA-example-123, retry=1"))
                .isEqualTo("apiKey=***, retry=1");
    }

    @Test
    @DisplayName("Bearer 접두사가 있어도 실제 토큰까지 마스킹된다")
    void bearer_토큰() {
        assertThat(SecretMaskingConverter.mask("authorization: Bearer abc.def.ghi"))
                .isEqualTo("authorization=***");
    }

    @Test
    @DisplayName("텔레그램 봇 토큰은 URL 안에 있어도 마스킹된다")
    void 텔레그램_토큰() {
        String message = "호출 실패 https://api.telegram.org/bot123456789:AbCdEfGhIjKlMnOpQrStUvWxYz0123456789/sendMessage";
        assertThat(SecretMaskingConverter.mask(message)).doesNotContain("AbCdEf").contains("***:***");
    }

    @Test
    @DisplayName("비밀이 아닌 키는 건드리지 않는다 — 마스킹된 로그는 복원할 수 없다")
    void 일반_값_유지() {
        String message = "dedupeKey=LOW_STOCK:SKU-001 sku=SKU-001 quantity=3";
        assertThat(SecretMaskingConverter.mask(message)).isEqualTo(message);
    }

    @Test
    @DisplayName("camelCase 복합어 안의 token 은 오탐하지 않는다 — 수집 커서가 지워지면 안 된다")
    void 복합어_오탐_방지() {
        String message = "쿠팡 수집 nextToken=CAoQzxyz 페이지 3";
        assertThat(SecretMaskingConverter.mask(message)).isEqualTo(message);
    }
}
