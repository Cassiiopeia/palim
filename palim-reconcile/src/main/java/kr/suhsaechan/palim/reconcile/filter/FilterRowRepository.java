package kr.suhsaechan.palim.reconcile.filter;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FilterRowRepository extends JpaRepository<FilterRow, UUID> {

    List<FilterRow> findByDefinitionIdOrderBySideAscOrdinalAsc(UUID definitionId);

    void deleteByDefinitionIdAndSide(UUID definitionId, FilterSide side);
}
