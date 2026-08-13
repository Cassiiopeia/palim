package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 요청이 잘못된 것을 <b>서버 고장으로 보고하지 않는다.</b>
 *
 * <p>주소를 직접 고쳐 들어오거나 링크가 깨지면 필요한 값이 빠진 요청이 들어온다. 이것을 500 으로
 * 다루면 두 가지를 잃는다 — 사용자는 오타 하나에 「서버 오류」를 보고 자기가 고칠 수 있는 일인
 * 줄 모르고, 로그에는 봇이 훑을 때마다 ERROR 가 쌓여 <b>진짜 장애가 그 사이에 묻힌다.</b>
 */
@AutoConfigureMockMvc
class MalformedRequestIntegrationTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;

    /** 이 화면은 «어느 설정의» 이력인지를 받아야 열린다. 그 값 없이 들어온 경우다. */
    @Test
    @WithMockUser
    @DisplayName("필요한 값이 빠지면 400 이다")
    void 값이_빠지면_400() throws Exception {
        mockMvc.perform(get("/settings/system/history"))
                .andExpect(status().isBadRequest());
    }

    /** 주소의 식별자 자리에 엉뚱한 글자가 온 경우. */
    @Test
    @WithMockUser
    @DisplayName("식별자 형식이 틀리면 400 이다")
    void 형식이_틀리면_400() throws Exception {
        mockMvc.perform(get("/connectors/{id}/runs", "이건-식별자가-아니다"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 형식은 맞지만 <b>없는</b> 것을 가리키는 요청은 이 규칙과 다르다. 그때는 화면이 «없다» 고
     * 말해야 하므로 400 이 아니다 — 둘을 섞으면 «주소가 틀렸다» 와 «지운 것이다» 를 구분할 수 없다.
     */
    @Test
    @WithMockUser
    @DisplayName("형식은 맞고 대상만 없으면 400 이 아니다")
    void 대상만_없으면_400이_아니다() throws Exception {
        mockMvc.perform(get("/connectors/{id}/runs", UUID.randomUUID()))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("주소가 틀린 것과 대상이 없는 것은 다르게 답해야 한다")
                        .isNotEqualTo(400));
    }
}
