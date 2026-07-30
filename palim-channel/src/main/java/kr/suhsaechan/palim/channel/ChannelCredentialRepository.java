package kr.suhsaechan.palim.channel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelCredentialRepository extends JpaRepository<ChannelCredential, UUID> {

    List<ChannelCredential> findByChannelId(UUID channelId);

    Optional<ChannelCredential> findByChannelIdAndCredentialKey(UUID channelId, String credentialKey);

    void deleteByChannelId(UUID channelId);
}
