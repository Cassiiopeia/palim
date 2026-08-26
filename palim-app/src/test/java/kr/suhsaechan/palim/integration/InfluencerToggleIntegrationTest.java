package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import kr.suhsaechan.palim.automation.influencer.InfluencerFeature;
import kr.suhsaechan.palim.common.config.SystemConfigService;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 꺼 둔 기능이 <b>정말로 꺼져 있는가</b>.
 *
 * <p>이 제품은 재고 대조 전용으로 방향을 틀었다. 인플루언서는 준비 중이라 기본으로 꺼져 있고,
 * 코드는 지우지 않는다 — 설정에서 켜면 그대로 되살아나야 한다.
 *
 * <p><b>메뉴만 감추면 감춰지지 않는다.</b> 이 화면들에는 접근 규칙이 하나도 없어서, 로그인한
 * 사람은 주소만 알면 그대로 다 썼다. 특히 위험한 것은 보는 화면이 아니라 <b>누르면 돈이
 * 나가는 것</b>이다 — AI 심사와 채널 수집은 외부 요금과 하루치 할당량을 쓴다.
 *
 * <p>그래서 「막혔다」 를 사람 눈이 아니라 시험이 말하게 한다.
 */
@AutoConfigureMockMvc
class InfluencerToggleIntegrationTest extends IntegrationTest {

    private static final UUID ANY = UUID.fromString("00000000-0000-7000-8000-0000000000ff");

    @Autowired private MockMvc mockMvc;
    @Autowired private SystemConfigService configService;
    @Autowired private InfluencerFeature influencerFeature;

    @AfterEach
    void turnOff() {
        // 다음 시험이 「기본은 꺼짐」 을 전제한다. 켠 채로 끝내면 그 전제가 조용히 깨진다.
        configService.update(InfluencerFeature.ENABLED, "false", "test");
    }

    /** 새로 올린 사람이 만나는 상태. 아무것도 안 했는데 켜져 있으면 안 된다. */
    @Test
    @DisplayName("기본값은 꺼짐이다")
    void defaultsToOff() {
        assertThat(influencerFeature.isEnabled())
                .as("준비 중인 기능이 기본으로 켜져 있으면 새로 올린 사람이 먼저 만난다")
                .isFalse();
    }

    /**
     * 주소로 들어가도 막힌다.
     *
     * <p>사이드바에 없는 <b>채널 상세</b>를 반드시 포함한다. 메뉴에서 안 보이는 화면일수록
     * 「막았다」 고 착각하기 쉽다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/influencer/grades",
            "/influencer/rising",
            "/influencer/trends",
            "/influencer/campaigns",
            "/influencer/campaigns/00000000-0000-7000-8000-0000000000ff"
                    + "/channels/00000000-0000-7000-8000-0000000000ff",
    })
    @WithMockUser
    @DisplayName("꺼져 있으면 화면 주소가 막힌다")
    void blocksScreens(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().is3xxRedirection());
    }

    /**
     * <b>돈이 나가는 입구</b>가 남아 있으면 감춘 의미가 없다.
     *
     * <p>유효한 CSRF 토큰을 붙여도 막혀야 한다 — 화면을 감춰도 토큰은 다른 화면에서 얻을 수
     * 있기 때문이다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/influencer/campaigns",
            "/influencer/seeds",
            "/influencer/trends/aggregate",
            "/influencer/campaigns/00000000-0000-7000-8000-0000000000ff/ai-review",
            "/influencer/campaigns/00000000-0000-7000-8000-0000000000ff/score",
            "/influencer/campaigns/00000000-0000-7000-8000-0000000000ff"
                    + "/channels/00000000-0000-7000-8000-0000000000ff/review",
            "/influencer/campaigns/00000000-0000-7000-8000-0000000000ff"
                    + "/channels/00000000-0000-7000-8000-0000000000ff/quote",
    })
    @WithMockUser
    @DisplayName("꺼져 있으면 값을 바꾸는 요청도 막힌다")
    void blocksMutations(String path) throws Exception {
        mockMvc.perform(post(path).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * 메뉴가 사라지되 <b>레이아웃은 끝까지</b> 그려진다.
     *
     * <p>이 저장소에는 표현식이 터지면 500 이 아니라 200 인 채로 페이지가 중간에서 끊기는
     * 함정이 있다. 잘려도 화면은 열린 것처럼 보이므로 둘을 한 번에 못 박는다.
     */
    @Test
    @WithMockUser
    @DisplayName("꺼져 있으면 메뉴에서 사라진다")
    void hidesMenu() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/influencer/"))))
                .andExpect(RenderAssertions.fullyRendered());
    }

    /** 「인플루언서를 껐더니 대조가 멈췄다」 를 즉시 잡는 자리. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/", "/reconcile", "/reconcile/units", "/connectors",
            "/connectors/models", "/settings/system"})
    @WithMockUser
    @DisplayName("꺼져 있어도 재고 대조는 그대로 돈다")
    void reconcileKeepsWorking(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered());
    }

    /**
     * 켜면 되살아난다 — 재기동 없이.
     *
     * <p>「설정 한 줄로 되돌린다」 가 실제로 성립하는지의 유일한 증거다. 여기서 화면이 끝까지
     * 그려지는지까지 보는 이유는, 전체 화면 시험에서 뺀 보장을 이 시험이 이어받기 때문이다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/influencer/grades", "/influencer/rising",
            "/influencer/trends", "/influencer/campaigns"})
    @WithMockUser
    @DisplayName("켜면 화면이 되살아나고 끝까지 그려진다")
    void turnsBackOn(String path) throws Exception {
        configService.update(InfluencerFeature.ENABLED, "true", "test");

        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode());
    }

    /** 켜면 메뉴도 함께 돌아온다. 주소만 열리고 메뉴는 없으면 켠 것이 아니다. */
    @Test
    @WithMockUser
    @DisplayName("켜면 메뉴도 돌아온다")
    void menuComesBack() throws Exception {
        configService.update(InfluencerFeature.ENABLED, "true", "test");

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/influencer/grades")))
                .andExpect(RenderAssertions.fullyRendered());
    }
}
