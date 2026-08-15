package kr.suhsaechan.palim.web.connector;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.run.ConnectorRunRepository;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연동을 지운다 — <b>지워도 되는 것만.</b>
 *
 * <p>DB 에 외래키가 없어 <b>지우는 것 자체는 아무것도 막아주지 않는다.</b> 자료를 담은 연동을
 * 지우면 대조가 다음 날 아침 조용히 깨지고, 화면에는 「비교할 재고가 없습니다」 만 떠서 원인을
 * 알 수 없다. 몇 주 뒤에 「그때 뭘 지웠더라」 를 되짚게 된다.
 *
 * <p>그래서 규칙은 <b>「쓴 적 없는 것만 지우고, 그 외에는 끈다」</b> 이다. 끄면 수집이 멈추고
 * 목록에서 꺼진 것으로 보이지만, 담긴 자료와 대조 정의는 그대로 산다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorRemovalService {

    private final ConnectorRepository connectors;
    private final ConnectorRunRepository runs;
    private final ReconcileDefinitionRepository definitions;

    /**
     * 지워도 되는지, 안 된다면 왜인지.
     *
     * <p>막는 이유를 <b>사람 말로</b> 돌려준다. 「지울 수 없습니다」 만으로는 무엇을 해야 하는지
     * 알 수 없다.
     *
     * @return 지워도 되면 {@code null}, 아니면 막는 사유
     */
    @Transactional(readOnly = true)
    public String blockedReason(UUID connectorId) {
        Connector connector = connectors.findById(connectorId).orElse(null);
        if (connector == null) {
            return "이미 없는 연동입니다.";
        }
        if (!runs.findByConnectorIdOrderByStartedAtDesc(connectorId).isEmpty()) {
            return "이 연동으로 자료를 담은 적이 있습니다. 지우면 그 자료가 어디서 왔는지 "
                    + "설명할 수 없게 됩니다. 대신 「끄기」 를 쓰세요.";
        }
        List<ReconcileDefinition> using = definitions.findByIsActiveTrueOrderByCode().stream()
                .filter(definition -> connector.getCode().equals(definition.getLeftSource())
                        || connector.getCode().equals(definition.getRightSource()))
                .toList();
        if (!using.isEmpty()) {
            return "대조가 이 연동을 쓰고 있습니다(%s). 지우면 대조가 다음 실행부터 깨집니다."
                    .formatted(using.getFirst().getName());
        }
        return null;
    }

    /**
     * 정말로 지운다.
     *
     * <p>실행 기록도 대조 참조도 없는 연동만 여기 온다 — 「만들어 두고 쓰지 않은 것」 이다.
     * 그런 것까지 남겨 두면 목록이 지저분해지고, 어느 것이 진짜 도는 연동인지 흐려진다.
     */
    @Transactional
    public void remove(UUID connectorId) {
        connectors.findById(connectorId).ifPresent(connector -> {
            log.warn("연동을 지웁니다 — 커넥터={}({}) (실행 기록·대조 참조가 없어 지울 수 있는 것)",
                    connector.getCode(), connectorId);
            connectors.delete(connector);
        });
    }

    /** 끄기. 수집은 멈추되 담긴 자료와 대조 정의는 그대로 산다. */
    @Transactional
    public void changeEnabled(UUID connectorId, boolean enabled) {
        connectors.findById(connectorId).ifPresent(connector -> {
            connector.changeEnabled(enabled);
            connectors.save(connector);
            log.info("연동을 {} — 커넥터={}", enabled ? "켰습니다" : "껐습니다", connector.getCode());
        });
    }
}
