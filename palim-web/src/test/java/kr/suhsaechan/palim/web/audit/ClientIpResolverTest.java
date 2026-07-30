package kr.suhsaechan.palim.web.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientIpResolverTest {

    @Test
    @DisplayName("IPv6 루프백은 127.0.0.1 로 정규화한다")
    void ipv6_루프백() {
        assertThat(ClientIpResolver.normalize("0:0:0:0:0:0:0:1")).isEqualTo("127.0.0.1");
        assertThat(ClientIpResolver.normalize("::1")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 는 IPv4 표기로 바꾼다")
    void ipv4_매핑() {
        assertThat(ClientIpResolver.normalize("::ffff:10.203.255.1")).isEqualTo("10.203.255.1");
    }

    @Test
    @DisplayName("일반 IPv4 · IPv6 는 그대로 둔다")
    void 일반_주소() {
        assertThat(ClientIpResolver.normalize("10.104.102.191")).isEqualTo("10.104.102.191");
        assertThat(ClientIpResolver.normalize("2001:db8::7")).isEqualTo("2001:db8::7");
    }

    @Test
    @DisplayName("비어 있으면 unknown — NULL 이면 화면에서 빈 칸이 되어 오해를 준다")
    void 빈_값() {
        assertThat(ClientIpResolver.normalize(null)).isEqualTo("unknown");
        assertThat(ClientIpResolver.normalize(" ")).isEqualTo("unknown");
    }
}
