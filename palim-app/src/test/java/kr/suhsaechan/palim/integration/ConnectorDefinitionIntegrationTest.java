package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.MappingStatus;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 연동 정의 계층 검증.
 *
 * <p>애플리케이션 검증이 아니라 <b>DB 제약</b>이 지키는 것들을 확인한다. 동시 요청에서는
 * 애플리케이션 검사가 뚫리므로, 유일성 같은 불변식은 인덱스가 보장해야 한다.
 */
class ConnectorDefinitionIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private JdbcClient jdbcClient;
    @Autowired private TargetModelRepository targetModelRepository;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private ConnectorMappingRepository mappingRepository;

    @Test
    @DisplayName("기본 테넌트가 마이그레이션으로 생성된다")
    void 기본_테넌트가_생성된다() {
        int count = jdbcClient.sql("SELECT count(*)::int FROM tenant WHERE code = 'default'")
                .query(Integer.class).single();

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("목표 모델의 자연키 목록이 JSONB 로 저장되고 그대로 읽힌다")
    void 자연키_목록이_왕복한다() {
        TargetModel saved = targetModelRepository.save(TargetModel.builtin(
                TENANT, "rt_model_" + UUID.randomUUID(), "왕복 검증", "std_stock_snapshot",
                List.of("source", "base_at", "item_ref")));

        TargetModel found = targetModelRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getNaturalKeyFields())
                .containsExactly("source", "base_at", "item_ref");
    }

    @Test
    @DisplayName("커스텀 모델은 JSONB 저장으로 만들어진다")
    void 커스텀_모델은_JSONB_다() {
        TargetModel custom = targetModelRepository.save(TargetModel.custom(
                TENANT, "rt_custom_" + UUID.randomUUID(), "커스텀", List.of("external_id")));

        assertThat(custom.isCustom()).isTrue();
        assertThat(custom.getTableName()).as("커스텀은 정식 테이블을 갖지 않는다").isNull();
    }

    @Test
    @DisplayName("커넥터당 ACTIVE 매핑은 하나뿐이다 — DB 가 막는다")
    void ACTIVE_매핑은_하나뿐이다() {
        Connector connector = connectorRepository.save(newConnector());

        ConnectorMapping first = ConnectorMapping.draft(TENANT, connector.getId(), 1, Map.of());
        first.activate();
        mappingRepository.saveAndFlush(first);

        ConnectorMapping second = ConnectorMapping.draft(TENANT, connector.getId(), 2, Map.of());
        second.activate();

        assertThatThrownBy(() -> mappingRepository.saveAndFlush(second))
                .as("부분 유니크 인덱스가 두 번째 ACTIVE 를 막아야 한다")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("DRAFT 매핑은 여러 개 존재할 수 있다")
    void DRAFT_는_여러개_가능하다() {
        Connector connector = connectorRepository.save(newConnector());

        mappingRepository.saveAndFlush(
                ConnectorMapping.draft(TENANT, connector.getId(), 1, Map.of()));
        mappingRepository.saveAndFlush(
                ConnectorMapping.draft(TENANT, connector.getId(), 2, Map.of()));

        assertThat(mappingRepository.findByConnectorIdOrderByVersionDesc(connector.getId()))
                .hasSize(2);
    }

    @Test
    @DisplayName("확정 전 매핑은 ACTIVE 조회에 잡히지 않는다")
    void 확정_전에는_ACTIVE_가_없다() {
        Connector connector = connectorRepository.save(newConnector());
        mappingRepository.saveAndFlush(
                ConnectorMapping.draft(TENANT, connector.getId(), 1, Map.of()));

        assertThat(mappingRepository.findByConnectorIdAndStatus(
                connector.getId(), MappingStatus.ACTIVE)).isEmpty();
    }

    @Test
    @DisplayName("증분 커서는 INCREMENTAL 일 때만 전진한다")
    void FULL_모드는_커서를_전진시키지_않는다() {
        Connector full = newConnector();

        full.advanceCursor("2026-08-12T00:00:00Z");

        assertThat(full.getCursorValue())
                .as("FULL 모드에 커서가 남으면 다음 실행이 구간을 건너뛴다").isNull();
    }

    @Test
    @DisplayName("INCREMENTAL 로 바꾸면 커서가 전진한다")
    void INCREMENTAL_은_커서를_전진시킨다() {
        Connector connector = newConnector();
        connector.enableIncremental("updated_at");

        connector.advanceCursor("2026-08-12T00:00:00Z");

        assertThat(connector.getCursorValue()).isEqualTo("2026-08-12T00:00:00Z");
    }

    private Connector newConnector() {
        TargetModel model = targetModelRepository.save(TargetModel.builtin(
                TENANT, "rt_m_" + UUID.randomUUID(), "모델", "std_stock_snapshot",
                List.of("item_ref")));
        return Connector.of(TENANT, "rt_c_" + UUID.randomUUID(), "커넥터", model.getId(),
                SourceType.UPLOAD, "EA");
    }
}
