package kr.suhsaechan.palim.automation.influencer.discover;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.automation.influencer.domain.DiscoverySource;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscoveryCursorRepository extends JpaRepository<DiscoveryCursor, UUID> {

    Optional<DiscoveryCursor> findBySourceAndCursorKey(DiscoverySource source, String cursorKey);

    /**
     * 가장 오래 안 돌린 키부터.
     *
     * <p>한 번도 안 돈 키(null)가 먼저 나와야 하므로 nulls first 를 명시한다 — PostgreSQL 의
     * ASC 기본값은 nulls last 다.
     */
    @org.springframework.data.jpa.repository.Query("""
            select c from DiscoveryCursor c
            where c.source = :source
            order by c.lastRunAt asc nulls first
            """)
    List<DiscoveryCursor> findNextTargets(DiscoverySource source, Limit limit);
}
