package kr.suhsaechan.palim.notification.secret;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSecretRepository extends JpaRepository<NotificationSecret, UUID> {

    Optional<NotificationSecret> findBySecretName(String secretName);

    boolean existsBySecretName(String secretName);

    void deleteBySecretName(String secretName);
}
