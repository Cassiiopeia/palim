package kr.suhsaechan.palim.reconcile.match;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 짝 없음 표시 저장소. */
public interface UnpairedItemRepository extends JpaRepository<UnpairedItem, UUID> {

    Optional<UnpairedItem> findBySourceAndItemRef(String source, String itemRef);

    List<UnpairedItem> findBySourceInOrderBySourceAscItemRefAsc(List<String> sources);
}
