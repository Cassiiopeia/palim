package kr.suhsaechan.palim.automation.influencer.trend;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrendKeywordRepository extends JpaRepository<TrendKeyword, UUID> {

    Optional<TrendKeyword> findByWeekStartAndCategoryCodeAndKeyword(
            LocalDate weekStart, String categoryCode, String keyword);

    List<TrendKeyword> findByWeekStartAndCategoryCodeOrderByFrequencyDesc(
            LocalDate weekStart, String categoryCode, Limit limit);

    List<TrendKeyword> findByWeekStart(LocalDate weekStart);

    /** 보드가 어느 주까지 집계됐는지 확인한다. */
    @org.springframework.data.jpa.repository.Query(
            "select max(t.weekStart) from TrendKeyword t")
    Optional<LocalDate> findLatestWeekStart();
}
