package kr.suhsaechan.palim.common.config;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemConfigHistoryRepository extends JpaRepository<SystemConfigHistory, UUID> {

    List<SystemConfigHistory> findByConfigKeyOrderByChangedAtDesc(String configKey, Limit limit);

    List<SystemConfigHistory> findAllByOrderByChangedAtDesc(Limit limit);
}
