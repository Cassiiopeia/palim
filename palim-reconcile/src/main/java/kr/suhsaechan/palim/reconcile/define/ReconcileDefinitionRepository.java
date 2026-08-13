package kr.suhsaechan.palim.reconcile.define;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 대조 정의 저장소. 테넌트 격리는 Hibernate 필터가 자동으로 건다. */
public interface ReconcileDefinitionRepository
        extends JpaRepository<ReconcileDefinition, UUID> {

    Optional<ReconcileDefinition> findByCode(String code);

    List<ReconcileDefinition> findByIsActiveTrueOrderByCode();
}
