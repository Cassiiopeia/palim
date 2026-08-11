package kr.suhsaechan.palim.common.config;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemConfigRepository extends JpaRepository<SystemConfig, UUID> {

    Optional<SystemConfig> findByConfigKey(String configKey);

    List<SystemConfig> findByCategoryOrderBySortOrderAsc(String category);

    List<SystemConfig> findByEditableTrueOrderByCategoryAscSortOrderAsc();

    /** 키 접두사로 묶어 읽는다 — 예: {@code influencer.scoring.} 하위 전체. */
    List<SystemConfig> findByConfigKeyStartingWithOrderByConfigKeyAsc(String prefix);
}
