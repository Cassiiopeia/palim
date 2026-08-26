package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.notification.delivery.DeliverySettingService;
import kr.suhsaechan.palim.notification.secret.NotificationSecretService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 메일 서버를 <b>화면에서</b> 넣을 수 있는가.
 *
 * <p>설정 파일에 두면 값을 바꿀 때마다 재배포해야 한다. 화면에서 넣으려면 비밀번호를 담아야
 * 하고, 담는 순간 그것이 새는 길이 생기지 않도록 하는 것이 이 시험의 절반이다.
 *
 * <p><b>실제 메일 서버에 접속하지 않는다.</b> 서버 정보가 없으면 연결을 아예 열지 않는 것이
 * 그 장치다 — 시험 환경에는 그 정보가 없다.
 */
@AutoConfigureMockMvc
class DeliverySettingIntegrationTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DeliverySettingService deliverySettingService;
    @Autowired private NotificationSecretService secrets;

    /** 아무것도 안 넣은 상태가 기본이다. 그때 메일은 쌓이기만 하고 나가지 않는다. */
    @Test
    @WithMockUser
    @DisplayName("아직 넣지 않았으면 대기 중이라고 말한다")
    void saysWaitingWhenBlank() throws Exception {
        mockMvc.perform(get("/settings/delivery"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("메일이 나가지 않고 대기 중입니다")))
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode());
    }

    @Test
    @WithMockUser
    @DisplayName("메일 서버를 저장하면 다시 읽힌다")
    void savesSmtp() throws Exception {
        mockMvc.perform(post("/settings/delivery/smtp")
                        .param("smtpHost", "smtp.example.invalid")
                        .param("smtpPort", "587")
                        .param("smtpUsername", "sender")
                        .param("fromAddress", "palim@example.invalid")
                        .param("useStartTls", "true")
                        .param("smtpPassword", "s3cret")
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        var setting = deliverySettingService.get();
        assertThat(setting.getSmtpHost()).isEqualTo("smtp.example.invalid");
        assertThat(setting.getSmtpPort()).isEqualTo(587);
        assertThat(deliverySettingService.hasPassword()).isTrue();
    }

    /**
     * 저장된 비밀번호가 <b>화면으로 되돌아오지 않는다.</b>
     *
     * <p>확인용으로 한 번만 보여주는 자리가 있으면 그 자리가 곧 유출 경로가 된다.
     */
    @Test
    @WithMockUser
    @DisplayName("비밀번호는 화면에 다시 나오지 않는다")
    void neverEchoesPassword() throws Exception {
        secrets.put(NotificationSecretService.SMTP_PASSWORD, "top-secret-value");

        mockMvc.perform(get("/settings/delivery"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("top-secret-value"))))
                .andExpect(content().string(containsString("등록됨")));
    }

    /**
     * 비우면 그대로 둔다.
     *
     * <p>그러지 않으면 다른 값 하나를 고치려고 화면을 열 때마다 비밀번호를 다시 쳐야 하고,
     * 잊고 저장하면 <b>발송만 조용히 실패한다.</b>
     */
    @Test
    @WithMockUser
    @DisplayName("비밀번호를 비우고 저장하면 기존 것이 남는다")
    void blankPasswordKeepsExisting() throws Exception {
        secrets.put(NotificationSecretService.SMTP_PASSWORD, "keep-me");

        mockMvc.perform(post("/settings/delivery/smtp")
                        .param("smtpHost", "smtp.example.invalid")
                        .param("smtpPort", "465")
                        .param("fromAddress", "palim@example.invalid")
                        .param("smtpPassword", "")
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(secrets.find(NotificationSecretService.SMTP_PASSWORD))
                .as("비우면 «바꾸지 않겠다» 는 뜻이다")
                .contains("keep-me");
    }

    /** 주소 하나가 잘못되면 발송 전체가 실패한다. 저장할 때 걸러야 며칠 뒤에 알아채지 않는다. */
    @Test
    @WithMockUser
    @DisplayName("받는 주소가 형식에 맞지 않으면 저장이 막힌다")
    void rejectsBadRecipient() throws Exception {
        mockMvc.perform(post("/settings/delivery/recipients")
                        .param("recipients", "이건주소가아님")
                        .param("mailScope", "DIGEST_ONLY")
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));

        assertThat(deliverySettingService.get().recipientList()).isEmpty();
    }

    @Test
    @WithMockUser
    @DisplayName("요약 보내는 시각을 바꾸면 저장된다")
    void savesDigestTime() throws Exception {
        mockMvc.perform(post("/settings/delivery/schedule")
                        .param("digestHour", "7")
                        .param("digestMinute", "30")
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        assertThat(deliverySettingService.get().digestTime().toString()).isEqualTo("07:30");
    }

    /** 준비되지 않은 상태에서 테스트 메일을 누르면 <b>연결을 열지 않고</b> 무엇이 빠졌는지 말한다. */
    @Test
    @WithMockUser
    @DisplayName("준비 전에는 테스트 메일이 서버에 접속하지 않는다")
    void testMailDoesNotDialWhenUnconfigured() throws Exception {
        mockMvc.perform(post("/settings/delivery/test-mail")
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));
    }
}
