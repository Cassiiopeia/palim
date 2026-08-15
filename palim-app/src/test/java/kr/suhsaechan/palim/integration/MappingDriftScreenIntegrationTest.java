package kr.suhsaechan.palim.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMap;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMapRepository;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>「시험은 됐는데 적재만 실패」</b> 를 화면이 미리 말하는가.
 *
 * <p>시험은 최신 판(확정 전 초안 포함)으로 돌고 적재는 확정판으로만 돈다. 일부러 그렇게
 * 만들었다 — 확정 전에 결과를 보는 것이 시험의 목적이고, 적재는 「어느 정의로 넣은 자료인가」 를
 * 나중에 설명할 수 있어야 한다.
 *
 * <p><b>그런데 화면이 그 차이를 말하지 않았다.</b> 초안을 고쳐 시험에 성공한 뒤 확정을 안 하면
 * 적재는 옛 확정판으로 돌아 전부 실패하는데, 사람은 같은 화면에서 같은 버튼을 눌렀으므로 무엇이
 * 달랐는지 알 길이 없다. 실제로 그 상태가 만들어져 있었고, 옛 확정판은 자동 생성된 빈 뼈대라
 * 전 행이 실패했다.
 */
@AutoConfigureMockMvc
class MappingDriftScreenIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ConnectorRepository connectors;
    @Autowired private ConnectorMappingRepository mappings;
    @Autowired private TargetModelRepository targetModels;
    @Autowired private ConnectorFieldMapRepository fieldMaps;

    private Connector connector;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        TargetModel model = targetModels.findByTenantIdAndCode(TENANT, "std_stock_snapshot")
                .orElseThrow();
        connector = connectors.save(Connector.of(TENANT,
                "drift-" + UUID.randomUUID().toString().substring(0, 8),
                "판 어긋남 시험", model.getId(), SourceType.HTTP_API, "EA"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ConnectorMapping draft(int version) {
        return mappings.save(ConnectorMapping.draft(TENANT, connector.getId(), version,
                Map.of("fields", List.of("A", "B"))));
    }

    /** 「사람이 손댄 판」 을 만든다 — 이어 둔 칸이 있는가로 가른다. */
    private void fieldMap(ConnectorMapping mapping) {
        fieldMaps.save(ConnectorFieldMap.of(TENANT, mapping.getId(), "A", "item_ref",
                Map.of(), 1));
    }

    /** 확정한 적이 없으면 적재 자체가 막혀 있다. 눌러 보고 알게 하지 않는다. */
    @Test
    @WithMockUser
    @DisplayName("한 번도 확정하지 않았으면 그렇다고 말한다")
    void 확정한_적_없음() throws Exception {
        fieldMap(draft(1));

        mockMvc.perform(get("/connectors/{id}/mapping", connector.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(content().string(Matchers.containsString("아직 한 번도 확정하지 않았습니다")));
    }

    /**
     * <b>이 화면이 이 시험의 전부다.</b> 초안이 확정판보다 앞서 있으면 시험과 적재가 다른
     * 규칙으로 돈다 — 그 사실과 <b>무엇을 해야 하는지</b>를 함께 말해야 한다.
     */
    @Test
    @WithMockUser
    @DisplayName("초안이 확정판보다 앞서 있으면 경고하고 할 일을 말한다")
    void 초안이_앞서_있음() throws Exception {
        ConnectorMapping first = draft(1);
        first.activate();
        mappings.save(first);
        fieldMap(first);
        fieldMap(draft(2));

        mockMvc.perform(get("/connectors/{id}/mapping", connector.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                // 판 번호가 «어느 쪽» 에 붙었는지가 이 화면의 값어치다. 둘이 뒤바뀌면
                // 문구가 정확히 반대를 말하는데, 그냥 있는지만 보면 그걸 못 잡는다.
                .andExpect(content().string(Matchers.matchesPattern(
                        "(?s).*시험</b>은 방금 고친 것\\(<span[^>]*>v2</span>\\).*")))
                .andExpect(content().string(Matchers.matchesPattern(
                        "(?s).*적재</b>는 확정해 둔 옛 것\\(<span[^>]*>v1</span>\\).*")))
                // 「다릅니다」 만으로는 무엇을 해야 하는지 알 수 없다
                .andExpect(content().string(Matchers.containsString("「연결 확정」을 먼저 누르세요")));
    }

    /**
     * <b>「다시 받아오기」 가 만든 빈 초안에서는 경고가 뜨면 안 된다.</b>
     *
     * <p>이 상태는 사람이 무엇을 고친 것이 아니다 — 확정 직후 칸 구조만 갱신해도 만들어진다.
     * 그런데 판 번호는 갈리므로, 「칸이 있는가」 를 안 보면 경고가 뜬다. 그 문구는 사실과
     * 반대이고(실제로는 시험이 실패하고 적재는 멀쩡하다), 문구가 권하는 「연결 확정」 을
     * 누르면 멀쩡한 확정판이 내려가고 빈 판이 올라가 <b>그때부터 적재가 전 행 실패한다.</b>
     * 경고가 사고를 권하는 셈이 된다.
     */
    @Test
    @WithMockUser
    @DisplayName("칸이 없는 빈 초안에서는 확정을 권하지 않는다")
    void 빈_초안에는_확정을_권하지_않는다() throws Exception {
        ConnectorMapping active = draft(1);
        active.activate();
        mappings.save(active);
        fieldMap(active);
        draft(2);

        mockMvc.perform(get("/connectors/{id}/mapping", connector.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(content().string(
                        Matchers.not(Matchers.containsString("「연결 확정」을 먼저 누르세요"))))
                // 대신 «할 일» 은 말해야 한다 — 침묵하면 왜 시험이 실패하는지 모른다
                .andExpect(content().string(Matchers.containsString("아직 이어 둔 칸이 없습니다")));
    }

    /** 어긋나지 않았으면 조용해야 한다. 늘 뜨는 경고는 아무도 안 읽는다. */
    @Test
    @WithMockUser
    @DisplayName("확정판과 최신 판이 같으면 경고하지 않는다")
    void 어긋나지_않음() throws Exception {
        ConnectorMapping only = draft(1);
        only.activate();
        mappings.save(only);
        fieldMap(only);

        mockMvc.perform(get("/connectors/{id}/mapping", connector.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(content().string(
                        Matchers.not(Matchers.containsString("「연결 확정」을 먼저 누르세요"))));
    }
}
