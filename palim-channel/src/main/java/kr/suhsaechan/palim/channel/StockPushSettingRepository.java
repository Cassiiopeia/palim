package kr.suhsaechan.palim.channel;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockPushSettingRepository extends JpaRepository<StockPushSetting, UUID> {

    /** 단일 행 설정이므로 첫 행을 읽는다. */
    Optional<StockPushSetting> findFirstByOrderByCreatedAtAsc();
}
