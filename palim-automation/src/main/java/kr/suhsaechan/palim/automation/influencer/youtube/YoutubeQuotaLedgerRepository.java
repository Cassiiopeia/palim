package kr.suhsaechan.palim.automation.influencer.youtube;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface YoutubeQuotaLedgerRepository extends JpaRepository<YoutubeQuotaLedger, UUID> {

    Optional<YoutubeQuotaLedger> findByUsageDate(LocalDate usageDate);
}
