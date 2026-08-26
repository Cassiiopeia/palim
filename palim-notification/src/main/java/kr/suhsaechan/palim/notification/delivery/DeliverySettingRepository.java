package kr.suhsaechan.palim.notification.delivery;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliverySettingRepository extends JpaRepository<DeliverySetting, UUID> {
}
