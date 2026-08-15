package kr.suhsaechan.palim.connector.script;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostScriptRepository extends JpaRepository<PostScript, UUID> {

    /** 실제로 돌 것들. 꺼 둔 것은 건너뛴다. */
    List<PostScript> findByConnectorIdAndStatusAndIsEnabledTrueOrderBySortOrder(
            UUID connectorId, PostScriptStatus status);

    /** 화면 목록. 꺼 둔 것도 보여야 켜고 끌 수 있다. */
    List<PostScript> findByConnectorIdAndStatusOrderBySortOrder(
            UUID connectorId, PostScriptStatus status);

    Optional<PostScript> findByConnectorIdAndNameAndStatus(
            UUID connectorId, String name, PostScriptStatus status);

    /** 다음 버전 번호를 정할 때 쓴다. */
    List<PostScript> findByConnectorIdAndNameOrderByVersionDesc(UUID connectorId, String name);
}
