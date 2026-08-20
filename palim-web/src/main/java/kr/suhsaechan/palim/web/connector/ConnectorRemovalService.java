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
 * 연동을 지운다 — <b>딸린 것까지 함께.</b>
 *
 * <p>여태 이 클래스는 「자료를 담은 적이 있으면 못 지운다」 였다. 뜻은 옳았지만 결과는
 * 막다른 길이었다 — 실행 이력을 지우는 경로가 어디에도 없어서, 한 번이라도 돈 연동은
 * 영원히 지울 수 없었다. 목록의 「지우기」 는 눌러도 빨간 경고만 뜨는 죽은 버튼이었다.
 *
 * <p>진짜 원인은 <b>DB 에 외래키가 없다는 것</b> 이었고, 없는 외래키를 여기 if 문으로
 * 흉내 내면서 {@code ON DELETE CASCADE} 자리에 「거부」 를 넣은 것이다. 그래서 관계를
 * DB 로 옮겼다({@code V33__connector_cascade.sql}). 지금은 두 가지가 갈린다.
 *
 * <ul>
 *   <li><b>실행 이력·담긴 자료</b> — 연동에 딸린 부산물이다. 외래키가 함께 지운다.
 *       남겨 두면 어느 연동의 것인지 아무도 답할 수 없는 행만 남는다
 *   <li><b>대조 정의</b> — 연동을 {@code code} 문자열로 가리키는 <b>독립된 개체</b> 다.
 *       외래키를 걸 수 없고, 지우면 대조가 다음 날 아침 조용히 깨져 화면에는
 *       「비교할 재고가 없습니다」 만 뜬다. 이쪽은 여전히 막는다
 * </ul>
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
     * <p>막는 이유를 <b>사람 말로</b> 돌려준다. 「지울 수 없습니다」 만으로는 무엇을 해야
     * 하는지 알 수 없다 — 어느 대조를 먼저 손봐야 하는지까지 말한다.
     *
     * <p>실행 이력은 더 이상 막지 않는다. 함께 지워지기 때문이다.
     *
     * @return 지워도 되면 {@code null}, 아니면 막는 사유
     */
    @Transactional(readOnly = true)
    public String blockedReason(UUID connectorId) {
        Connector connector = connectors.findById(connectorId).orElse(null);
        if (connector == null) {
            return "이미 없는 연동입니다.";
        }
        List<ReconcileDefinition> using = usedBy(connector);
        if (!using.isEmpty()) {
            return ("대조가 이 연동을 쓰고 있습니다(%s). 먼저 그 대조를 끄거나 다른 원천으로 "
                    + "바꾼 뒤에 지우세요.").formatted(using.getFirst().getName());
        }
        return null;
    }

    /**
     * 지우면 무엇이 함께 사라지는가.
     *
     * <p>지우기 직전에 <b>숫자로</b> 보여주기 위한 것이다. 「정말 지울까요?」 만 묻는 확인창은
     * 아무 정보도 주지 않아 사람이 그냥 누른다. 「실행 이력 44건이 함께 지워집니다」 여야
     * 손이 멈춘다.
     *
     * @return 함께 지워질 실행 이력 건수. 담긴 재고 자료는 이 실행들에 매달려 있다
     */
    @Transactional(readOnly = true)
    public int runCount(UUID connectorId) {
        return runs.findByConnectorIdOrderByStartedAtDesc(connectorId).size();
    }

    /**
     * 정말로 지운다.
     *
     * <p>딸린 것은 외래키가 지운다 — 매핑·후처리 스크립트·실행 이력, 그리고 그 실행들이 담은
     * 재고 자료까지. 여기서 목록을 손으로 관리하지 않는 이유는, 새 표가 붙을 때마다 이 코드를
     * 고치는 것을 <b>언젠가 잊기 때문</b> 이다. 잊으면 주인 없는 행이 조용히 쌓인다.
     */
    @Transactional
    public void remove(UUID connectorId) {
        connectors.findById(connectorId).ifPresent(connector -> {
            log.warn("연동을 지웁니다 — 커넥터={}({}), 함께 지워질 실행 이력={}건",
                    connector.getCode(), connectorId, runCount(connectorId));
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

    /** 이 연동을 원천으로 쓰는 대조들. 외래키가 아니라 코드 문자열로 이어져 있다. */
    private List<ReconcileDefinition> usedBy(Connector connector) {
        return definitions.findByIsActiveTrueOrderByCode().stream()
                .filter(definition -> connector.getCode().equals(definition.getLeftSource())
                        || connector.getCode().equals(definition.getRightSource()))
                .toList();
    }
}
