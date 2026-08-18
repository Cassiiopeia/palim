package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.rule.NormalizationEngine;
import kr.suhsaechan.palim.reconcile.rule.NormalizationPreview;
import kr.suhsaechan.palim.reconcile.rule.NormalizationRule;
import kr.suhsaechan.palim.reconcile.rule.NormalizationRuleRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 이름 다듬기 규칙을 <b>화면에서</b> 다룰 수 있는가.
 *
 * <p>이 화면이 없으면 이 프로그램은 한 회사에서만 돈다. 표기 습관은 회사마다 다른데 — 어디는
 * 「/」 로 규격을 나누고 어디는 「[2026-10-17]」 로 유통기한을 붙인다 — 그것을 넣을 자리가
 * 없으면 다른 곳에 가져다 놓는 순간 「이을 수 있는 것」 이 늘 비어 있게 되고, 품목을 전부
 * 손으로 이어야 한다.
 *
 * <p>그리고 <b>저장 전에 걸어 볼 수 있어야 한다.</b> 잘못 고쳤을 때 눈에 보이는 것은 「이을 수
 * 있는 것이 줄었다」 뿐이고 왜인지는 어디에도 안 나온다.
 */
@AutoConfigureMockMvc
class NormalizationRuleScreenIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    /**
     * 이 시험이 만든 규칙에 붙이는 표시.
     *
     * <p>규칙은 <b>전역</b>이라 한 시험이 남긴 것이 다음 시험의 다듬기 결과를 바꾼다. 실제로
     * 「끄면 빠진다」 가 「넣으면 반영된다」 가 남긴 규칙 때문에 통과해 버렸다 — 껐는데도 다른
     * 규칙이 같은 일을 하고 있었던 것이다.
     */
    private static final String MARK = "시험규칙";

    @Autowired private MockMvc mockMvc;
    @Autowired private NormalizationRuleRepository rules;
    @Autowired private NormalizationEngine engine;
    @Autowired private NormalizationPreview preview;
    @Autowired private JdbcClient jdbcClient;

    private String source;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        source = "src-" + UUID.randomUUID().toString().substring(0, 6);
        snapshot("A1", "클래식 227g [2026-10-17]");
        snapshot("A2", "코코아 227g [2026-10-18]");
    }

    @AfterEach
    void tearDown() {
        // 이 시험이 만든 것만 지운다 — 시드 규칙은 다른 시험의 전제다.
        rules.deleteAll(rules.findAllByOrderBySortOrder().stream()
                .filter(rule -> rule.getName().contains(MARK))
                .toList());
        engine.clearCache();
        TenantContext.clear();
    }

    private void snapshot(String itemRef, String name) {
        var at = Instant.now().truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC);
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             quantity, base_quantity, base_unit, raw_item_name,
                             created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, '', '',
                                1, 1, 'EA', :name, :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", at)
                .param("source", source)
                .param("name", name)
                .update();
    }

    /** 규칙이 화면에 보여야 사람이 손댈 수 있다. */
    @Test
    @WithMockUser
    @DisplayName("규칙 화면이 지금 쓰는 규칙과 실제 품명 미리보기를 함께 보여준다")
    void 화면이_열린다() throws Exception {
        mockMvc.perform(get("/reconcile/rules").locale(Locale.KOREAN))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(content().string(Matchers.containsString("지금 쓰는 규칙")))
                // 지어낸 예시가 아니라 지금 담긴 실제 품명이어야 한다
                .andExpect(content().string(Matchers.containsString("클래식 227g [2026-10-17]")));
    }

    /**
     * <b>저장 전에 걸어 본다.</b>
     *
     * <p>규칙을 잘못 고쳤을 때 눈에 보이는 것은 「이을 수 있는 것이 줄었다」 뿐이고 왜인지는
     * 어디에도 안 나온다. 저장 전에 실제 품명이 어떻게 바뀌는지 보여주면 그 왕복이 사라진다.
     *
     * <p>바뀌는 «모습» 은 표본을 직접 줘서 확인한다. 화면이 뽑는 표본은 담긴 자료 전체에서
     * 오므로, 다른 시험의 품명이 함께 담겨 있으면 어느 열두 개가 뽑힐지 정해지지 않는다.
     */
    @Test
    @WithMockUser
    @DisplayName("넣기 전에 걸어 보면 결과가 어떻게 달라지는지 보여준다")
    void 걸어_본다() throws Exception {
        NormalizationRule bracket = NormalizationPreview.candidate(TENANT,
                MARK + " 대괄호를 뺀다", "\\[[^\\]]*\\]", "", Integer.MAX_VALUE);

        List<NormalizationPreview.Line> lines =
                preview.preview(bracket, List.of("클래식 227g [2026-10-17]"));

        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.candidate())
                    .as("대괄호가 떨어져 나가야 두 시스템의 이름이 같아진다")
                    .isEqualTo("클래식227g");
            assertThat(line.changed())
                    .as("바뀌지 않았다면 그 규칙은 이 자료에 아무 일도 안 하는 것이다")
                    .isTrue();
        });

        // 화면도 «새 규칙까지» 칸을 그려야 사람이 그 차이를 본다
        mockMvc.perform(get("/reconcile/rules")
                        .locale(Locale.KOREAN)
                        .param("name", MARK + " 대괄호를 뺀다")
                        .param("pattern", "\\[[^\\]]*\\]")
                        .param("replacement", ""))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(content().string(Matchers.containsString("새 규칙까지")));

        assertThat(rules.findAllByOrderBySortOrder())
                .as("걸어 보기는 아무것도 저장하지 않는다")
                .noneMatch(rule -> rule.getName().contains(MARK));
    }

    /** 넣으면 실제로 다듬기가 달라진다 — 캐시를 안 비우면 옛 패턴으로 계속 돈다. */
    @Test
    @WithMockUser
    @DisplayName("규칙을 넣으면 다듬기 결과가 바로 달라진다")
    void 넣으면_반영된다() throws Exception {
        String before = engine.normalize("클래식 227g [2026-10-17]");

        mockMvc.perform(post("/reconcile/rules")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("name", MARK + " 대괄호를 뺀다")
                        .param("pattern", "\\[[^\\]]*\\]")
                        .param("replacement", ""))
                .andExpect(status().is3xxRedirection());

        assertThat(engine.normalize("클래식 227g [2026-10-17]"))
                .as("캐시를 안 비우면 저장됐다고 말하면서 결과가 안 바뀐다")
                .isNotEqualTo(before)
                .isEqualTo("클래식227g");
    }

    /**
     * <b>잘못된 정규식은 저장되지 않는다.</b>
     *
     * <p>저장되면 엔진이 조용히 건너뛴다 — 화면은 저장됐다고 하는데 매칭만 이유 없이 줄어들고,
     * 사람은 원인을 찾을 방법이 없다.
     */
    @Test
    @WithMockUser
    @DisplayName("잘못된 정규식은 이유를 말하고 저장하지 않는다")
    void 잘못된_정규식() throws Exception {
        List<NormalizationRule> before = rules.findAllByOrderBySortOrder();

        mockMvc.perform(post("/reconcile/rules")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("name", MARK + " 망가진 규칙")
                        .param("pattern", "([")
                        .param("replacement", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError",
                        Matchers.containsString("정규식이 잘못됐습니다")));

        assertThat(rules.findAllByOrderBySortOrder()).hasSameSizeAs(before);
    }

    /**
     * <b>오래 걸리는 미리보기는 요청 스레드를 붙잡지 못한다.</b>
     *
     * <p>사람이 정규식을 직접 넣는 화면이다. 계산이 오래 걸리는 규칙이 들어오면 <b>톰캣은 이미
     * 돌고 있는 요청 스레드를 죽이지 않는다</b> — 브라우저가 기다리다 포기해도 그 스레드는 계속
     * 돈다. 몇 번 반복하면 스레드 풀이 마르고 서버 전체가 응답을 멈춘다.
     *
     * <p>여기서는 되돌아가는 패턴 대신 <b>표본을 많이 줘서</b> 제한 시간을 넘긴다. 자바 21의
     * 정규식 엔진은 {@code (a+)+$} 같은 고전적인 폭주 패턴을 최적화로 흘려보내므로, 짧은
     * 입력으로는 폭주를 재현할 수 없다. 확인해야 할 것은 「어떤 규칙이 폭주하는가」 가 아니라
     * <b>「오래 걸리면 요청 스레드가 풀려나는가」</b> 이고, 그것은 이 방식으로 확인된다.
     *
     * <p>끊는 것이 실제로 먹는지도 함께 본다 — 자바의 {@code Matcher} 는 인터럽트를 보지 않아,
     * 입력을 감싸지 않으면 {@code cancel(true)} 가 아무 일도 하지 않는다.
     */
    @Test
    @DisplayName("미리보기가 오래 걸리면 제한 시간에 끊고 요청을 풀어 준다")
    void 오래_걸리면_끊는다() {
        NormalizationRule slow = NormalizationPreview.candidate(TENANT,
                MARK + " 느린 규칙", "(.*a){15}", "x", Integer.MAX_VALUE);
        List<String> manySamples = java.util.stream.IntStream.range(0, 20_000)
                .mapToObj(i -> "a".repeat(60) + "b" + i)
                .toList();

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> preview.preview(slow, manySamples))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NORMALIZATION_PREVIEW_TIMEOUT);

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - startedAt))
                .as("요청 스레드가 제한 시간 안에 반드시 풀려나야 한다")
                .isLessThan(java.time.Duration.ofSeconds(15));
    }

    /**
     * 순서가 결과를 바꾼다 — 괄호를 떼기 전에 공백을 지우면 괄호 규칙이 안 맞는다.
     *
     * <p>시드 규칙을 옮기지 않고 <b>이 시험이 만든 둘</b>을 옮긴다. 순서는 전역이라 시드를
     * 흔들면 다른 시험의 전제가 조용히 바뀐다.
     */
    @Test
    @WithMockUser
    @DisplayName("순서를 올리면 실제로 앞에서 적용된다")
    void 순서를_바꾼다() throws Exception {
        NormalizationRule first = rules.save(NormalizationRule.of(TENANT,
                MARK + " 먼저", "가", "", 9001));
        NormalizationRule second = rules.save(NormalizationRule.of(TENANT,
                MARK + " 나중", "나", "", 9002));

        mockMvc.perform(post("/reconcile/rules/{id}/move", second.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("up", "true"))
                .andExpect(status().is3xxRedirection());

        List<NormalizationRule> mine = rules.findAllByOrderBySortOrder().stream()
                .filter(rule -> rule.getName().contains(MARK))
                .toList();
        assertThat(mine.getFirst().getId())
                .as("순서를 바꿨다고 말했으면 실제로 바뀌어야 한다")
                .isEqualTo(second.getId());
        assertThat(mine.get(1).getId()).isEqualTo(first.getId());
    }

    /** 껐다 켜 보며 매칭 개수가 어떻게 변하는지 확인하는 것이 이 화면에서 제일 흔한 일이다. */
    @Test
    @WithMockUser
    @DisplayName("규칙을 끄면 다듬기에서 빠진다")
    void 끄면_빠진다() throws Exception {
        NormalizationRule bracket = rules.save(NormalizationRule.of(TENANT,
                MARK + " 대괄호",
                "\\[[^\\]]*\\]", "", 500));
        engine.clearCache();
        assertThat(engine.normalize("클래식 227g [2026-10-17]")).isEqualTo("클래식227g");

        mockMvc.perform(post("/reconcile/rules/{id}/toggle", bracket.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN))
                .andExpect(status().is3xxRedirection());

        assertThat(engine.normalize("클래식 227g [2026-10-17]"))
                .as("꺼진 규칙이 계속 적용되면 끄기가 거짓말이 된다")
                .isNotEqualTo("클래식227g");
    }
}
