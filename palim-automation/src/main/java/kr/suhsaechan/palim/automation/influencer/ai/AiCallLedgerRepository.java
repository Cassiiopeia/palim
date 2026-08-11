package kr.suhsaechan.palim.automation.influencer.ai;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiCallLedgerRepository extends JpaRepository<AiCallLedger, UUID> {

    Optional<AiCallLedger> findByUsageDate(LocalDate usageDate);
}
