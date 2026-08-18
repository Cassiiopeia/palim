package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.Intake;
import kr.suhsaechan.palim.connector.define.MappingStatus;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.hamcrest.Matchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>자동 수집이 깨졌을 때 파일로 계속 돌릴 수 있는가.</b>
 *
 * <p>공개 API 가 없는 원천은 상대 화면이 쓰는 경로를 그대로 흉내 내서 가져온다 — 로그인
 * 페이지에서 공개키를 뽑고, 암호화한 값을 보내 세션을 받고, 화면이 쓰는 조회 요청을 같은
 * 형식으로 보낸다. <b>상대가 그중 무엇 하나만 바꿔도 그날로 깨진다.</b>
 *
 * <p>그때 사람이 파일을 받아 올려 계속 돌릴 수 있어야 업무가 안 멈춘다. 그리고 그 파일은
 * <b>같은 원천 이름으로</b> 들어가야 한다 — 별도 연동을 새로 만들면 원천 이름이 달라져 그동안
 * 묶어 둔 품목이 통째로 무용지물이 된다(07-DECISIONS 045).
 */
@AutoConfigureMockMvc
class FileFallbackIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");
    /** 기준일은 «업무 기준» 이다. 서버가 어느 시간대에 있든 사장님의 오늘이 오늘이다. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    @Autowired private MockMvc mockMvc;
    @Autowired private ConnectorRepository connectors;
    @Autowired private ConnectorMappingRepository mappings;
    @Autowired private TargetModelRepository targetModels;
    @Autowired private JdbcClient jdbcClient;

    private Connector connector;
    private String source;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        source = "wms-" + UUID.randomUUID().toString().substring(0, 6);
        UUID modelId = targetModels.findByTenantIdAndCode(TENANT, "std_stock_snapshot")
                .orElseThrow().getId();
        // 스스로 가져오는 연동 — 상대 화면 경로를 흉내 내는 쪽이다.
        connector = connectors.save(Connector.of(TENANT, source, "물류 재고", modelId,
                SourceType.HTTP_API, "EA"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 상대가 주는 파일.
     *
     * <p>열 이름이 <b>API 칸 이름과 다르다</b>는 것이 이 시험의 전제다 — API 는
     * {@code item_ref}·{@code quantity}, 내려받은 표는 「품목코드」·「재고수량」.
     */
    private MockMultipartFile stockFile() {
        String csv = """
                품목코드,품목명,재고수량
                A-001,클래식 850g,307
                A-002,코코아 227g,789
                """;
        return new MockMultipartFile("file", "재고현황.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * <b>엑셀 열 이름은 API 칸 이름과 다르다.</b>
     *
     * <p>칸 맞추기를 한 벌로 두면 파일을 올리는 순간 API 용 칸이 걸려 전 행이 실패한다.
     * 그래서 길마다 따로 둔다.
     */
    @Test
    @WithMockUser
    @DisplayName("파일용 칸 맞추기가 자동 수집용과 따로 저장된다")
    void 길마다_칸이_따로() throws Exception {
        mockMvc.perform(multipart("/connectors/{id}/schema", connector.getId())
                        .file(stockFile())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("intake", "FILE")
                        .param("headerRow", "1"))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                // 엑셀 열 이름이 그대로 보여야 그것으로 맞출 수 있다
                .andExpect(content().string(Matchers.containsString("재고수량")));

        assertThat(mappings.findByConnectorIdAndIntakeOrderByVersionDesc(
                connector.getId(), Intake.FILE))
                .as("파일 길 초안이 따로 만들어져야 한다")
                .isNotEmpty();
        assertThat(mappings.findByConnectorIdAndIntakeOrderByVersionDesc(
                connector.getId(), Intake.AUTO))
                .as("자동 수집용 초안을 덮으면 둘 다 못 쓰게 된다")
                .isEmpty();
    }

    /**
     * <b>파일로 담은 자료가 「같은 원천 이름」 으로 들어간다.</b>
     *
     * <p>이것이 이 기능의 존재 이유다. 원천 이름이 달라지면 그동안 묶어 둔 품목이 전부
     * 무용지물이 되고, 급할 때 쓰는 길인데 그때 묶기부터 다시 하라는 셈이 된다.
     */
    @Test
    @WithMockUser
    @DisplayName("파일로 담아도 같은 원천 이름으로 들어간다")
    void 같은_원천으로_들어간다() throws Exception {
        mockMvc.perform(multipart("/connectors/{id}/schema", connector.getId())
                        .file(stockFile())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("intake", "FILE").param("headerRow", "1"))
                .andExpect(status().isOk());

        // 엑셀 열 이름으로 칸을 맞추고 확정한다.
        mockMvc.perform(post("/connectors/{id}/mapping", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("intake", "FILE")
                        .param("sourceFields", "품목코드", "재고수량")
                        .param("targetKeys", "item_ref", "quantity")
                        .param("schemaFields", "품목코드", "품목명", "재고수량"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/connectors/{id}/activate", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("intake", "FILE"))
                .andExpect(status().is3xxRedirection());

        assertThat(mappings.findByConnectorIdAndIntakeAndStatus(
                connector.getId(), Intake.FILE, MappingStatus.ACTIVE))
                .as("파일 길 확정판이 있어야 담을 수 있다")
                .isPresent();

        mockMvc.perform(multipart("/connectors/{id}/run", connector.getId())
                        .file(stockFile())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("mode", "LIVE").param("headerRow", "1"))
                .andExpect(status().is3xxRedirection());

        List<String> refs = jdbcClient.sql("""
                        SELECT item_ref FROM std_stock_snapshot
                         WHERE tenant_id = :tenantId AND source = :source
                         ORDER BY item_ref
                        """)
                .param("tenantId", TENANT)
                .param("source", source)
                .query(String.class)
                .list();
        assertThat(refs)
                .as("원천 이름이 달라지면 묶어 둔 품목이 통째로 무용지물이 된다")
                .containsExactly("A-001", "A-002");
    }

    /**
     * 어디서 받는지 <b>안내가 화면에 있어야 한다.</b>
     *
     * <p>급할 때 쓰는 우회로인데 그때 방법을 찾아다니게 하면 우회로가 아니다. 그리고 상대
     * 사이트는 언젠가 바뀌므로 <b>사람이 그 자리에서 고칠 수</b> 있어야 한다.
     */
    @Test
    @WithMockUser
    @DisplayName("받는 방법 안내를 화면에서 적고 고칠 수 있다")
    void 안내를_고친다() throws Exception {
        mockMvc.perform(post("/connectors/{id}/file-guide", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("guide", "① 로그인 ② 재고관리 > 재고현황 ③ 엑셀 내려받기"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/connectors/{id}", connector.getId()).locale(Locale.KOREAN))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(content().string(Matchers.containsString("파일로 채우기")))
                .andExpect(content().string(
                        Matchers.containsString("재고관리 &gt; 재고현황")));
    }

    /**
     * 파일용 칸을 안 맞춰 뒀으면 <b>미리 해 두라고</b> 말한다.
     *
     * <p>「올리기 전에 알려 준다」 로는 부족하다. 그러면 <b>자동 수집이 멈춘 날에 처음</b>
     * 설정하게 되는데, 그날은 엑셀을 받는 동시에 칸까지 맞춰야 한다 — 우회로가 우회로가 아니다.
     * 그래서 평소에 화면이 준비 안 됐다고 말한다.
     */
    @Test
    @WithMockUser
    @DisplayName("파일용 칸이 없으면 미리 해 두라고 말한다")
    void 칸이_없으면_먼저_말한다() throws Exception {
        mockMvc.perform(get("/connectors/{id}", connector.getId()).locale(Locale.KOREAN))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(content().string(
                        Matchers.containsString("아직 파일로 채울 준비가 안 됐습니다")))
                .andExpect(content().string(Matchers.containsString("지금 한 번 해 두세요")));
    }

    /**
     * 받는 방법 안내는 <b>비어 있을 수 없다.</b>
     *
     * <p>전에는 연결을 저장할 때 한 번 심어서, 그 전에 만든 연동에는 비어 있었다. 화면은
     * 「기본 안내 넣기」 단추를 눌러 달라고 했는데 — <b>단추를 눌러야 생기는 안내는 급할 때
     * 비어 있다.</b> 아무도 평소에 그 단추를 누르지 않기 때문이다.
     *
     * <p>그리고 안내는 「확인하신 뒤 적어 두세요」 가 아니라 <b>실제 경로</b>여야 한다. 그건
     * 제품이 할 일을 사람에게 미루는 것이다.
     */
    @Test
    @WithMockUser
    @DisplayName("안내를 한 번도 안 적어 둬도 실제 경로가 화면에 있다")
    void 안내는_비어_있지_않다() throws Exception {
        UUID modelId = targetModels.findByTenantIdAndCode(TENANT, "std_stock_snapshot")
                .orElseThrow().getId();
        // 프리셋을 알아보는 코드로 만든다 — 연동 코드 앞자리가 곧 어느 시스템인지다.
        Connector known = connectors.save(Connector.of(TENANT,
                "onewms-" + UUID.randomUUID().toString().substring(0, 6),
                "3자물류 재고", modelId, SourceType.HTTP_API, "EA"));

        mockMvc.perform(get("/connectors/{id}", known.getId()).locale(Locale.KOREAN))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                // 어느 화면에서 어떤 조건으로 받는지가 «그 자리에» 있어야 한다.
                .andExpect(content().string(Matchers.containsString("I100")))
                .andExpect(content().string(Matchers.containsString("창고 1번")))
                // 떠넘기는 문구가 남아 있으면 안 된다.
                .andExpect(content().string(
                        Matchers.not(Matchers.containsString("기본 안내 넣기"))));
    }

    /**
     * 파일이 <b>며칟날 기준</b>인지 사람이 고른다.
     *
     * <p>자동 수집은 지금 물어보니 오늘 것이 맞다. 그런데 사람이 받아 오는 파일은 어제 것일 수
     * 있다 — 자동 수집이 어제 멈췄다면 어제 기준으로 조회해 받는 것이 정상이다.
     *
     * <p>그때 오늘로 담으면 <b>어제 재고가 오늘 재고인 척</b> 들어간다. 대조는 어제 재고와
     * 오늘 재고를 견주게 되어 <b>없는 차이를 있다고 말하고</b>, 사람은 그것이 날짜 탓인 줄
     * 모르고 창고를 뒤진다.
     */
    @Test
    @WithMockUser
    @DisplayName("파일을 며칟날 기준으로 담을지 고른 대로 들어간다")
    void 기준일을_고른_대로() throws Exception {
        prepareFileMapping();

        LocalDate yesterday = LocalDate.now(BUSINESS_ZONE).minusDays(1);
        mockMvc.perform(multipart("/connectors/{id}/run", connector.getId())
                        .file(stockFile())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("mode", "LIVE").param("headerRow", "1")
                        .param("baseDate", yesterday.toString()))
                .andExpect(status().is3xxRedirection());

        List<Instant> baseAts = jdbcClient.sql("""
                        SELECT DISTINCT base_at FROM std_stock_snapshot
                         WHERE tenant_id = :tenantId AND source = :source
                        """)
                .param("tenantId", TENANT)
                .param("source", source)
                .query(Instant.class)
                .list();

        assertThat(baseAts)
                .as("고른 날짜로 담기지 않으면 대조가 없는 차이를 있다고 말한다")
                .containsExactly(yesterday.atStartOfDay(BUSINESS_ZONE).toInstant());
    }

    /** 파일 길의 칸을 맞춰 확정한다 — 담기 전에 반드시 있어야 한다. */
    private void prepareFileMapping() throws Exception {
        mockMvc.perform(multipart("/connectors/{id}/schema", connector.getId())
                        .file(stockFile())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("intake", "FILE").param("headerRow", "1"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/connectors/{id}/mapping", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("intake", "FILE")
                        .param("sourceFields", "품목코드", "재고수량")
                        .param("targetKeys", "item_ref", "quantity")
                        .param("schemaFields", "품목코드", "품목명", "재고수량"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/connectors/{id}/activate", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("intake", "FILE"))
                .andExpect(status().is3xxRedirection());
    }
}
