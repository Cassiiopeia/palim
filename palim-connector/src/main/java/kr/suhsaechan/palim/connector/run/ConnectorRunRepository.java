package kr.suhsaechan.palim.connector.run;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorRunRepository extends JpaRepository<ConnectorRun, UUID> {

    /**
     * 동시 실행 판정.
     *
     * <p>최종 보장은 부분 유니크 인덱스({@code ux_connector_run_running})가 한다. 이 조회는
     * 사람에게 이유를 알려주기 위한 것이다 — 인덱스 위반 예외만 던지면 원인을 알 수 없다.
     */
    boolean existsByConnectorIdAndStatus(UUID connectorId, RunStatus status);

    /** 기동할 때 「실행 중」인 채로 굳은 것을 찾는다. 그 실행을 하던 프로세스는 이미 없다. */
    List<ConnectorRun> findByStatus(RunStatus status);

    /** 되돌리기 대상 판정용. 마지막 LIVE 실행이 아니면 되돌릴 수 없다. */
    Optional<ConnectorRun> findFirstByConnectorIdAndRunModeOrderByStartedAtDesc(UUID connectorId,
                                                                               RunMode runMode);

    List<ConnectorRun> findByConnectorIdOrderByStartedAtDesc(UUID connectorId);
}
