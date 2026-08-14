package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMapRepository;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 화면에서 고른 것이 <b>그대로 저장되는가</b>.
 *
 * <p>사장님이 「품목 → key」 를 골라 저장했는데 실행하면 전 줄이 「품목이 비었다」로 떨어졌다.
 * 로그를 보니 엔진이 <b>고정값(빈 값)</b>을 넣고 있었다 — 고른 원천 칸을 쳐다보지도 않았다.
 *
 * <pre>
 * 고정값 적용 — 목표칸=item_ref 설정값='' 최종값=null
 * 필수 칸이 비어 이 행을 버린다 — 필수칸=item_ref 원천칸=key
 * </pre>
 *
 * <p>화면에는 <b>「key」 가 골라진 채로 보인다.</b> 그래서 사람은 자기가 뭘 잘못했는지 알 수
 * 없다. 이 테스트는 화면이 실제로 보내는 값을 그대로 되먹여, <b>고른 대로 저장되고 고른 대로
 * 실행되는지</b>를 끝까지 확인한다.
 */
@AutoConfigureMockMvc
class MappingFormRoundTripIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    /** 화면이 보내는 숨은 값을 그대로 뽑아 온다. 「어떻게 보낼 것 같다」 가 아니라 실제 값이다. */
    private static final Pattern HIDDEN = Pattern.compile(
            "<input[^>]*type=\"hidden\"[^>]*name=\"%s\"[^>]*value=\"([^\"]*)\"[^>]*>");

    @Autowired private MockMvc mockMvc;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private ConnectorMappingRepository mappingRepository;
    @Autowired private ConnectorFieldMapRepository fieldMapRepository;
    @Autowired private TargetModelRepository targetModelRepository;

    private Connector connector;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        connector = connectorRepository.save(Connector.of(TENANT,
                "roundtrip-" + UUID.randomUUID().toString().substring(0, 8),
                "왕복 시험", model.getId(), SourceType.HTTP_API, "EA"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 화면 → 저장 → 화면 → 저장. <b>두 번 저장해도 같은 것이 남아야 한다.</b>
     *
     * <p>한 번만 저장하고 끝나는 사람은 없다. 값을 고치러 다시 들어오고, 그때 화면이 보여주는
     * 것을 그대로 다시 보낸다. 그 왕복에서 규칙이 변질되면 <b>화면은 그대로인데 실행만
     * 달라진다</b> — 사람이 알아챌 방법이 없는 종류의 고장이다.
     */
    @Test
    @WithMockUser
    @DisplayName("고른 원천 칸이 저장되고, 다시 저장해도 그대로다")
    void 고른_대로_저장된다() throws Exception {
        // 원천 칸 목록을 먼저 화면에 알린다. 실제로는 상대에게서 받아 온다.
        List<String> schemaFields = List.of("key", "product_name", "stock_normal", "barcode");

        // 1차 저장 — 사람이 「품목 → key」·「수량 → stock_normal」 을 고른 상태
        Map<String, String> picked = new LinkedHashMap<>();
        picked.put("item_ref", "key");
        picked.put("quantity", "stock_normal");
        picked.put("raw_item_name", "product_name");
        save(schemaFields, picked);

        assertSaved("첫 저장");

        // 2차 저장 — 화면이 보여주는 것을 사람이 손대지 않고 그대로 다시 보낸다
        MvcResult page = mockMvc.perform(get("/connectors/{id}/mapping", connector.getId()))
                .andExpect(status().isOk())
                .andReturn();
        String html = page.getResponse().getContentAsString();

        assertThat(values(html, "transformTypes"))
                .as("화면이 「고정값」 을 보내면, 고른 원천 칸이 무시되고 빈 값이 담긴다")
                .doesNotContain("CONSTANT");

        resend(html, schemaFields);
        assertSaved("다시 저장");
    }

    /** 저장된 것이 「고른 칸을 그대로 읽는」 규칙인지 확인한다. */
    private void assertSaved(String when) {
        var mapping = mappingRepository.findByConnectorIdAndStatus(connector.getId(),
                kr.suhsaechan.palim.connector.define.MappingStatus.DRAFT).orElseThrow();
        var maps = fieldMapRepository.findByMappingIdOrderBySortOrder(mapping.getId());

        var itemRef = maps.stream()
                .filter(map -> "item_ref".equals(map.getTargetFieldKey()))
                .findFirst().orElseThrow(() -> new AssertionError(when + ": 품목 줄이 없다"));

        assertThat(itemRef.getSourceField())
                .as("%s: 고른 원천 칸이 사라졌다", when)
                .isEqualTo("key");
        assertThat(String.valueOf(itemRef.getTransformRule().get("type")))
                .as("%s: 「고정값」 으로 저장되면 원천 칸을 읽지 않고 빈 값을 넣는다 — "
                        + "화면에는 key 가 골라진 채로 보여 원인을 알 수 없다", when)
                .isNotEqualTo("CONSTANT");
    }

    /** 화면이 그린 숨은 값·선택값을 그대로 되돌려 보낸다. 사람이 손대지 않은 재저장이다. */
    private void resend(String html, List<String> schemaFields) throws Exception {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        values(html, "targetKeys").forEach(value -> form.add("targetKeys", value));
        values(html, "transformTypes").forEach(value -> form.add("transformTypes", value));
        values(html, "params").forEach(value -> form.add("params", value));
        selectedSources(html).forEach(value -> form.add("sourceFields", value));
        schemaFields.forEach(value -> form.add("schemaFields", value));

        mockMvc.perform(post("/connectors/{id}/mapping", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .params(form))
                .andExpect(status().is3xxRedirection());
    }

    private void save(List<String> schemaFields, Map<String, String> picked) throws Exception {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        picked.forEach((target, source) -> {
            form.add("targetKeys", target);
            form.add("sourceFields", source);
            form.add("transformTypes", "NONE");
            form.add("params", "");
        });
        schemaFields.forEach(value -> form.add("schemaFields", value));

        mockMvc.perform(post("/connectors/{id}/mapping", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .params(form))
                .andExpect(status().is3xxRedirection());
    }

    private static List<String> values(String html, String name) {
        Matcher matcher = Pattern.compile(HIDDEN.pattern().formatted(name)).matcher(html);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /** 각 select 에서 실제로 선택된 option 값. 브라우저가 보내는 것과 같다. */
    private static List<String> selectedSources(String html) {
        List<String> picked = new ArrayList<>();
        Matcher select = Pattern.compile(
                "<select[^>]*name=\"sourceFields\"[^>]*>(.*?)</select>", Pattern.DOTALL)
                .matcher(html);
        while (select.find()) {
            Matcher option = Pattern.compile(
                    "<option value=\"([^\"]*)\"[^>]*selected[^>]*>").matcher(select.group(1));
            picked.add(option.find() ? option.group(1) : "");
        }
        // 시스템이 채우는 줄은 select 가 없고 숨은 값으로만 나간다.
        values(html, "sourceFields").forEach(picked::add);
        return picked;
    }
}
