package kr.suhsaechan.palim.web.setup;

import java.util.ArrayList;
import java.util.List;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.MappingStatus;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileRunRepository;
import kr.suhsaechan.palim.reconcile.run.RunStatus;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitService;
import kr.suhsaechan.palim.web.connector.ConnectorAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 준비 상태판.
 *
 * <p>처음 오는 사람의 진입점이다. 흩어진 화면을 순서대로 잇고, 지금 막힌 곳을 짚어 준다.
 *
 * <p><b>사실만 말한다.</b> 만들어 둔 화면을 「준비 중」이라고 하면 사장님은 거기서 멈추는데,
 * 정작 사이드바에는 같은 기능이 링크로 떠 있다. 한 번 그렇게 굳은 적이 있어 이 원칙을 적어 둔다.
 */
@Service
@RequiredArgsConstructor
public class SetupService {

    private final ConnectorRepository connectorRepository;
    private final ConnectorMappingRepository mappingRepository;
    private final ReconcileUnitService unitService;
    private final ReconcileDefinitionRepository definitionRepository;
    private final ReconcileRunRepository runRepository;

    @Transactional(readOnly = true)
    public List<SetupStep> steps() {
        List<Connector> connectors =
                connectorRepository.findByTenantIdOrderByName(ConnectorAdminService.DEFAULT_TENANT);
        List<Connector> collecting = connectors.stream().filter(this::collects).toList();

        List<SetupStep> steps = new ArrayList<>();
        steps.add(connectionStep(connectors, collecting));
        steps.add(matchingStep(collecting));
        steps.add(reconcileStep(collecting));
        steps.add(scheduleStep(collecting));
        return steps;
    }

    /**
     * 자료가 실제로 들어오는 연동인가.
     *
     * <p>연결과 칸 맞추기가 <b>둘 다</b> 끝나야 한 줄이라도 들어온다. 연결만 보고 완료라고 하면
     * 며칠 뒤 「왜 데이터가 없지」로 알게 된다.
     */
    private boolean collects(Connector connector) {
        return connector.getConnectionStatus().isUsable()
                && mappingRepository
                        .findByConnectorIdAndStatus(connector.getId(), MappingStatus.ACTIVE)
                        .isPresent();
    }

    private SetupStep connectionStep(List<Connector> all, List<Connector> collecting) {
        if (all.isEmpty()) {
            return new SetupStep(1, "재고 가져오는 곳", SetupStep.State.ATTENTION,
                    "아직 연결한 시스템이 없습니다",
                    "전산·물류 시스템을 연결하세요", "/connectors/connect");
        }

        // 붙여는 뒀는데 칸을 안 맞춘 것이 있으면 그것이 다음 걸음이다. 새로 붙이는 것보다
        // 이미 붙인 것을 끝내는 편이 자료가 빨리 들어온다.
        Connector unfinished = all.stream()
                .filter(c -> !collecting.contains(c))
                .findFirst().orElse(null);
        if (unfinished != null) {
            return new SetupStep(1, "재고 가져오는 곳", SetupStep.State.ATTENTION,
                    "%s — %s".formatted(unfinished.getName(), nextActionOf(unfinished)),
                    unfinished.getConnectionStatus().isUsable()
                            ? "칸을 맞춰야 자료가 들어옵니다"
                            : unfinished.getConnectionStatus().getNextAction(),
                    "/connectors/" + unfinished.getId());
        }
        if (collecting.size() < 2) {
            return new SetupStep(1, "재고 가져오는 곳", SetupStep.State.ATTENTION,
                    "자료가 들어오는 곳 %d 군데 — 맞춰 보려면 둘이 필요합니다"
                            .formatted(collecting.size()),
                    "나머지 시스템을 연결하세요", "/connectors/connect");
        }
        return new SetupStep(1, "재고 가져오는 곳", SetupStep.State.DONE,
                "%d 군데에서 자료가 들어옵니다".formatted(collecting.size()), null, "/connectors");
    }

    /** 칸을 맞추지 않은 것과 연결이 덜 된 것을 한 문장으로 구분해 말한다. */
    private String nextActionOf(Connector connector) {
        return connector.getConnectionStatus().isUsable()
                ? "칸 맞추기가 남았습니다"
                : connector.getConnectionStatus().getLabel();
    }

    private SetupStep matchingStep(List<Connector> collecting) {
        if (collecting.size() < 2) {
            return new SetupStep(2, "품목 맞추기", SetupStep.State.WAITING,
                    "자료가 들어오는 곳이 둘이 되면 시작합니다", null, null);
        }
        int pending = unitService.pending().size();
        if (pending > 0) {
            return new SetupStep(2, "품목 맞추기", SetupStep.State.ATTENTION,
                    "확인을 기다리는 품목 %d 건".formatted(pending),
                    "같은 물건인지 보고 확인해 주세요", "/reconcile/units");
        }
        return new SetupStep(2, "품목 맞추기", SetupStep.State.DONE,
                "확인을 기다리는 품목이 없습니다", null, "/reconcile/units");
    }

    private SetupStep reconcileStep(List<Connector> collecting) {
        List<ReconcileDefinition> definitions =
                definitionRepository.findByIsActiveTrueOrderByCode();
        if (definitions.isEmpty()) {
            return new SetupStep(3, "대조 결과", SetupStep.State.ATTENTION,
                    collecting.size() < 2
                            ? "자료가 들어오는 곳이 둘이 되면 맞춰 볼 수 있습니다"
                            : "무엇과 무엇을 맞춰 볼지 아직 정하지 않았습니다",
                    "맞춰 볼 대상을 정하세요", "/reconcile");
        }
        boolean ranOnce = definitions.stream().anyMatch(d ->
                runRepository.findFirstByDefinitionIdAndStatusOrderByStartedAtDesc(
                        d.getId(), RunStatus.SUCCESS).isPresent());
        return new SetupStep(3, "대조 결과",
                ranOnce ? SetupStep.State.DONE : SetupStep.State.WAITING,
                ranOnce ? "맞춰 본 기록이 있습니다" : "아직 한 번도 맞춰 보지 않았습니다",
                null, "/reconcile");
    }

    /**
     * 매일 스스로 도는가.
     *
     * <p>수집이 도는 조건은 <b>시각만이 아니다</b> — 확정된 칸 맞추기가 있어야 하고 파일 업로드
     * 방식은 애초에 자동으로 돌지 않는다. 시각만 보고 «예약되어 있습니다» 라고 하면, 스케줄러는
     * 영원히 건너뛰는데 화면만 다 됐다고 말한다.
     */
    private SetupStep scheduleStep(List<Connector> collecting) {
        if (collecting.isEmpty()) {
            return new SetupStep(4, "매일 자동으로", SetupStep.State.WAITING,
                    "자료가 들어오기 시작하면 시각을 정할 수 있습니다", null, null);
        }
        List<Connector> schedulable = collecting.stream()
                .filter(c -> c.getSourceType() != SourceType.UPLOAD)
                .toList();
        if (schedulable.isEmpty()) {
            return new SetupStep(4, "매일 자동으로", SetupStep.State.WAITING,
                    "파일로 올리는 방식은 자동으로 돌지 않습니다", null, null);
        }
        Connector notScheduled = schedulable.stream()
                .filter(c -> !StringUtils.hasText(c.getScheduleCron()))
                .findFirst().orElse(null);
        if (notScheduled != null) {
            return new SetupStep(4, "매일 자동으로", SetupStep.State.ATTENTION,
                    "%s 의 수집 시각이 정해지지 않았습니다".formatted(notScheduled.getName()),
                    "몇 시에 가져올지 정하세요", "/connectors/" + notScheduled.getId());
        }
        return new SetupStep(4, "매일 자동으로", SetupStep.State.DONE,
                "%d 군데가 매일 스스로 가져옵니다".formatted(schedulable.size()), null, "/connectors");
    }
}
