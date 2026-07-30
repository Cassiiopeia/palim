package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import kr.suhsaechan.palim.channel.ChannelCredentialService;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널 인증정보가 암호화되어 저장되고 평문으로 조회되는지 검증한다 (설계서 6.2).
 */
@Transactional
class ChannelCredentialIntegrationTest extends IntegrationTest {

    @Autowired
    private ChannelCredentialService channelCredentialService;

    @Test
    @DisplayName("등록한 인증정보를 평문으로 조회할 수 있다")
    void 왕복이_동작한다() {
        channelCredentialService.put(ChannelCode.COUPANG, "accessKey", "AK-12345");
        channelCredentialService.put(ChannelCode.COUPANG, "secretKey", "SK-abcde");
        channelCredentialService.put(ChannelCode.COUPANG, "vendorId", "A00123456");

        assertThat(channelCredentialService.get(ChannelCode.COUPANG, "accessKey")).isEqualTo("AK-12345");
        assertThat(channelCredentialService.getAll(ChannelCode.COUPANG))
                .containsEntry("accessKey", "AK-12345")
                .containsEntry("secretKey", "SK-abcde")
                .containsEntry("vendorId", "A00123456");
    }

    @Test
    @DisplayName("같은 키를 다시 등록하면 값이 갱신된다")
    void 재등록하면_갱신된다() {
        channelCredentialService.put(ChannelCode.NAVER, "clientId", "old-value");
        channelCredentialService.put(ChannelCode.NAVER, "clientId", "new-value");

        assertThat(channelCredentialService.get(ChannelCode.NAVER, "clientId")).isEqualTo("new-value");
        assertThat(channelCredentialService.findKeys(ChannelCode.NAVER)).containsExactly("clientId");
    }

    @Test
    @DisplayName("등록되지 않은 키는 빈 값을 반환한다")
    void 없는_키는_빈_값이다() {
        assertThat(channelCredentialService.find(ChannelCode.ELEVENST, "apiKey")).isEmpty();
    }

    @Test
    @DisplayName("키 목록 조회는 값을 노출하지 않는다")
    void 키_목록은_값을_노출하지_않는다() {
        channelCredentialService.put(ChannelCode.LOTTEON, "authKey", "민감한-값");

        assertThat(channelCredentialService.findKeys(ChannelCode.LOTTEON))
                .containsExactly("authKey")
                .doesNotContain("민감한-값");
    }
}
