package kr.suhsaechan.palim.channel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널 설정·수집 상태 서비스 (F-01).
 */
@Service
@RequiredArgsConstructor
public class ChannelService {

    private final ChannelRepository channelRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public Channel registerIfAbsent(ChannelCode code, int collectIntervalSeconds) {
        return channelRepository.findByCode(code)
                .orElseGet(() -> channelRepository.save(Channel.register(code, collectIntervalSeconds)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enable(ChannelCode code) {
        getByCode(code).enable();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void disable(ChannelCode code) {
        getByCode(code).disable();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changeCollectInterval(ChannelCode code, int seconds) {
        getByCode(code).changeCollectInterval(seconds);
    }

    /** 수집 성공. 커서를 전진시키고 실패 카운터를 초기화한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCollectSuccess(ChannelCode code, Instant collectedUntil, Instant collectedAt) {
        getByCode(code).recordCollectSuccess(collectedUntil, collectedAt);
    }

    /**
     * 수집 실패.
     *
     * <p>커서를 전진시키지 않는다. 다음 시도에서 같은 구간을 다시 조회해야 주문이 유실되지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCollectFailure(ChannelCode code, Instant attemptedAt, String error) {
        getByCode(code).recordCollectFailure(attemptedAt, error);
    }

    @Transactional(readOnly = true)
    public Channel getByCode(ChannelCode code) {
        return channelRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, code));
    }

    @Transactional(readOnly = true)
    public Optional<Channel> findByCode(ChannelCode code) {
        return channelRepository.findByCode(code);
    }

    @Transactional(readOnly = true)
    public List<Channel> findAll() {
        return channelRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Channel> findEnabled() {
        return channelRepository.findAllByEnabledTrue();
    }

    /** 수집 시각이 도래한 채널 목록. 스케줄러가 이 목록만 처리한다. */
    @Transactional(readOnly = true)
    public List<Channel> findDue(Instant now) {
        return channelRepository.findAllByEnabledTrue().stream()
                .filter(channel -> channel.isDueAt(now))
                .toList();
    }

    /** 연속 실패가 임계치를 넘은 채널 — 경고 발송 대상 (A-10). */
    @Transactional(readOnly = true)
    public List<Channel> findFailing(int threshold) {
        return channelRepository.findByConsecutiveFailureCountGreaterThan(threshold);
    }
}
