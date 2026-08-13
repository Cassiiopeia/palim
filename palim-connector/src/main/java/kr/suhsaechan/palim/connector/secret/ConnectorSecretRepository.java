package kr.suhsaechan.palim.connector.secret;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorSecretRepository extends JpaRepository<ConnectorSecret, UUID> {

    Optional<ConnectorSecret> findByCredentialRefAndSecretName(String credentialRef,
                                                              String secretName);

    List<ConnectorSecret> findByCredentialRef(String credentialRef);

    void deleteByCredentialRef(String credentialRef);
}
