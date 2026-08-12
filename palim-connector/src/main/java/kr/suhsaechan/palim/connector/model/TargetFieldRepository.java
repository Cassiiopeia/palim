package kr.suhsaechan.palim.connector.model;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TargetFieldRepository extends JpaRepository<TargetField, UUID> {

    List<TargetField> findByTargetModelIdOrderBySortOrder(UUID targetModelId);

    boolean existsByTargetModelIdAndFieldKey(UUID targetModelId, String fieldKey);
}
