package kr.suhsaechan.palim.reconcile.rule;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 정규화 규칙 저장소. 적용 순서대로 돌려준다 — 순서가 바뀌면 결과가 달라진다. */
public interface NormalizationRuleRepository extends JpaRepository<NormalizationRule, UUID> {

    List<NormalizationRule> findByIsActiveTrueOrderBySortOrder();

    List<NormalizationRule> findAllByOrderBySortOrder();
}
