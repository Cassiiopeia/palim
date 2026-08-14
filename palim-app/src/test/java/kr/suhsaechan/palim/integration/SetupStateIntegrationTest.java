package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.web.connector.ConnectorAdminService;
import kr.suhsaechan.palim.web.setup.SetupStep;
import kr.suhsaechan.palim.web.setup.SetupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 준비 상태판이 <b>사실</b>을 말하는가.
 *
 * <p>이 값이 홈의 첫 화면이 된다. 여기서 「준비 중」이라고 하면 사장님은 이미 있는 기능 앞에서
 * 멈춘다. 실제로 그렇게 굳어 있었다 — 품목 맞추기·대조·자동 실행을 만들어 배포한 뒤에도
 * 화면은 계속 준비 중이라고 답했다.
 */
class SetupStateIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = ConnectorAdminService.DEFAULT_TENANT;

    @Autowired private SetupService setupService;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private TargetModelRepository targetModelRepository;

    /**
     * 이 테스트 클래스는 「연결이 하나도 없을 때」를 검증한다. 통합 테스트는 테스트 간에 같은
     * PostgreSQL 컨테이너를 공유해 데이터가 그대로 남는다(IntegrationTest 참고) — 다른 테스트
     * 클래스가 같은 테넌트에 남긴 연동이 있으면 "아무것도 없으면" 전제가 깨진다. 매 테스트 앞에서
     * 이 테넌트의 연동을 비워 시작점을 고정한다.
     */
    @BeforeEach
    void 연동을_비운다() {
        connectorRepository.findByTenantIdOrderByName(TENANT)
                .forEach(connectorRepository::delete);
    }

    /**
     * 「연결됐지만 칸을 안 맞춘」 상태를 만든다. 검증까지 마쳐야 수집 파이프라인이 도는데,
     * 등록만 하고 검증하지 않으면(NOT_CONFIGURED) 1단계가 "칸을 맞추세요"가 아니라 "인증정보를
     * 등록하세요"를 말해 이 테스트의 전제 자체가 성립하지 않는다.
     */
    private Connector connector(String name) {
        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        Connector connector = Connector.of(
                TENANT, "setup-" + UUID.randomUUID().toString().substring(0, 8),
                name, model.getId(), SourceType.HTTP_API, "EA");
        connector.markVerified(true);
        return connectorRepository.save(connector);
    }

    private SetupStep stepOf(List<SetupStep> steps, int order) {
        return steps.stream().filter(s -> s.order() == order).findFirst().orElseThrow();
    }

    /**
     * 만들어 둔 화면을 「준비 중」이라고 말하면 안 된다. 사장님은 그 앞에서 멈추는데, 정작
     * 사이드바에는 같은 기능이 링크로 떠 있다.
     */
    @Test
    @DisplayName("이미 만든 단계를 준비 중이라고 하지 않는다")
    void 준비_중이라고_하지_않는다() {
        List<SetupStep> steps = setupService.steps();

        assertThat(steps)
                .as("만들어 둔 것을 준비 중이라고 하면 거기서 길이 끊긴다")
                .noneSatisfy(step ->
                        assertThat(step.state()).isEqualTo(SetupStep.State.NOT_READY));
    }

    /**
     * 갈 곳이 있어야 「하러 가기」가 눌린다. 링크가 없으면 사장님은 사이드바에서 직접 찾아야 한다.
     */
    @Test
    @DisplayName("아직 할 일이 남은 단계는 갈 곳을 알려준다")
    void 갈_곳을_알려준다() {
        List<SetupStep> steps = setupService.steps();

        assertThat(steps)
                .filteredOn(step -> step.state() == SetupStep.State.ATTENTION)
                .as("손봐야 한다고 말하면서 어디로 가야 할지 안 알려주면 소용이 없다")
                .allSatisfy(step -> assertThat(step.link()).isNotBlank());
    }

    /**
     * 연결만으로는 자료가 한 줄도 안 들어온다 — 수집은 확정된 칸 맞추기를 요구한다. 연결
     * 두 개만으로 완료라고 하면 며칠 뒤 «왜 데이터가 없지» 로 알게 된다.
     */
    @Test
    @DisplayName("칸을 맞추지 않았으면 연결 단계가 끝난 것이 아니다")
    void 칸을_맞춰야_끝난다() {
        connector("전산");
        connector("물류");

        SetupStep first = stepOf(setupService.steps(), 1);

        assertThat(first.state())
                .as("연결만 해서는 수집이 돌지 않는다")
                .isNotEqualTo(SetupStep.State.DONE);
        assertThat(first.detail()).contains("칸");
    }

    /**
     * 아무것도 없을 때는 「첫 시스템을 붙이세요」 하나만 보여야 한다. 여러 개를 나열하면 무엇부터
     * 할지 다시 고민하게 된다.
     */
    @Test
    @DisplayName("아무것도 없으면 첫 걸음만 짚어 준다")
    void 첫_걸음만_짚는다() {
        SetupStep first = stepOf(setupService.steps(), 1);

        assertThat(first.state()).isEqualTo(SetupStep.State.ATTENTION);
        assertThat(first.link()).isEqualTo("/connectors/connect");
    }
}
