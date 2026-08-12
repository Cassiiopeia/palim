package kr.suhsaechan.palim.connector.run;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorRunErrorRepository extends JpaRepository<ConnectorRunError, UUID> {

    /** 실패 행 목록. 원천 순서대로 봐야 사람이 파일에서 찾아갈 수 있다. */
    List<ConnectorRunError> findByRunIdOrderByRowNumber(UUID runId);
}
