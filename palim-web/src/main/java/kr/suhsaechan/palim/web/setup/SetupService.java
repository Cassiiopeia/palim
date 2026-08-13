package kr.suhsaechan.palim.web.setup;

import java.util.ArrayList;
import java.util.List;
import kr.suhsaechan.palim.connector.define.ConnectionStatus;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.web.connector.ConnectorAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 준비 상태 계산.
 *
 * <p>위저드가 아니라 <b>상태판</b>이다. 위저드는 한 번 지나가면 버려지는데, 실제로는 인증키가
 * 만료되고 비밀번호가 바뀌고 품목이 새로 생긴다. 그때마다 처음부터 밟게 하면 쓰지 않게 된다.
 *
 * <p>상태판은 처음 오는 사람에게는 따라갈 순서로 보이고, 나중에는 점검판이 된다.
 */
@Service
@RequiredArgsConstructor
public class SetupService {

    private final ConnectorRepository connectorRepository;

    @Transactional(readOnly = true)
    public List<SetupStep> steps() {
        List<Connector> connectors =
                connectorRepository.findByTenantIdOrderByName(ConnectorAdminService.DEFAULT_TENANT);

        List<SetupStep> steps = new ArrayList<>();
        steps.add(connectionStep(connectors));
        steps.add(matchingStep(connectors));
        steps.add(reconcileStep());
        steps.add(scheduleStep(connectors));
        return steps;
    }

    /**
     * 1단계 — 원천 연결.
     *
     * <p>대사는 <b>두 곳</b>을 비교하는 일이라 하나만 연결해서는 시작할 수 없다. 그래서 개수를
     * 함께 보여준다 — "연결됨"만 뜨면 하나로 충분한 줄 알고 다음으로 넘어가게 된다.
     */
    private SetupStep connectionStep(List<Connector> connectors) {
        if (connectors.isEmpty()) {
            return new SetupStep(1, "원천 연결", SetupStep.State.ATTENTION,
                    "연결된 시스템이 없습니다",
                    "전산·물류 시스템을 연결하세요", "/connectors/connect");
        }

        long usable = connectors.stream()
                .filter(c -> c.getConnectionStatus().isUsable()).count();
        long needsAttention = connectors.stream()
                .filter(c -> c.getConnectionStatus().needsAttention()).count();

        if (needsAttention > 0) {
            Connector first = connectors.stream()
                    .filter(c -> c.getConnectionStatus().needsAttention())
                    .findFirst().orElseThrow();
            return new SetupStep(1, "원천 연결", SetupStep.State.ATTENTION,
                    "%d개 연결 중 %d개를 손봐야 합니다 — %s: %s"
                            .formatted(connectors.size(), needsAttention, first.getName(),
                                    first.getConnectionStatus().getLabel()),
                    first.getConnectionStatus().getNextAction(), "/connectors");
        }
        if (usable < 2) {
            return new SetupStep(1, "원천 연결", SetupStep.State.ATTENTION,
                    "연결 %d개 — 대사하려면 비교할 원천이 둘 필요합니다".formatted(usable),
                    "나머지 시스템을 연결하세요", "/connectors/connect");
        }
        return new SetupStep(1, "원천 연결", SetupStep.State.DONE,
                "연결 %d개 정상".formatted(usable), null, "/connectors");
    }

    private SetupStep matchingStep(List<Connector> connectors) {
        boolean ready = connectors.stream()
                .filter(c -> c.getConnectionStatus().isUsable()).count() >= 2;
        // 아직 구현 전이다. 숨기지 않고 보여주는 이유는 전체 그림을 알아야 지금 위치가
        // 이해되기 때문이다. "다 됐는데 왜 대사가 안 되지"를 막는다.
        return new SetupStep(2, "품목 매칭", SetupStep.State.NOT_READY,
                ready ? "연결이 끝났습니다. 매칭 화면은 준비 중입니다"
                        : "원천을 먼저 연결하세요",
                "준비 중", null);
    }

    private SetupStep reconcileStep() {
        return new SetupStep(3, "재고 대조", SetupStep.State.NOT_READY,
                "대사 엔진은 준비 중입니다", "준비 중", null);
    }

    private SetupStep scheduleStep(List<Connector> connectors) {
        boolean anyScheduled = connectors.stream().anyMatch(c -> c.getScheduleCron() != null);
        return new SetupStep(4, "매일 자동 실행",
                anyScheduled ? SetupStep.State.DONE : SetupStep.State.NOT_READY,
                anyScheduled ? "예약되어 있습니다" : "자동 실행은 준비 중입니다",
                anyScheduled ? null : "준비 중", null);
    }

    /** 손봐야 할 것이 있는가. 대시보드에서 배지로 알릴 때 쓴다. */
    @Transactional(readOnly = true)
    public boolean hasAttention() {
        return connectorRepository.findByTenantIdOrderByName(ConnectorAdminService.DEFAULT_TENANT)
                .stream()
                .map(Connector::getConnectionStatus)
                .anyMatch(ConnectionStatus::needsAttention);
    }
}
