package kr.suhsaechan.palim.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.error.BusinessException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채널 설정과 수집 상태 (F-01).
 *
 * <p>수집 주기는 채널별 호출 제한을 넘지 않도록 조절한다. 특히 쿠팡은 지속 초과 시 <b>영구
 * 차단</b>되므로 임계 도달 시 수집을 자동 중단한다(설계서 6.1).
 */
@Getter
@Entity
@Table(name = "channel")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Channel extends BaseTimeEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 20)
    private ChannelCode code;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private boolean enabled;

    /** 수집 주기(초). 웹 관리자에서 변경 가능하다 (F-01). */
    @Column(nullable = false)
    private int collectIntervalSeconds;

    /**
     * 수집 커서.
     *
     * <p>다음 수집은 이 시각에서 여유를 두고 겹쳐서 조회한다. 채널 API는 주문 시각이 지연
     * 반영되는 경우가 있어 구간을 정확히 이어붙이면 경계에서 주문이 누락되며, 중복은 유니크
     * 제약이 막지만 누락은 아무도 감지하지 못한다(설계서 5.4).
     */
    private Instant collectedUntil;

    private Instant lastCollectedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CollectStatus lastCollectStatus;

    @Column(length = 1000)
    private String lastCollectError;

    /** 연속 실패 횟수. 임계 도달 시 경고를 보내고 수집을 중단한다 (A-10). */
    @Column(nullable = false)
    private int consecutiveFailureCount;

    @Version
    private Long version;

    private Channel(ChannelCode code, String name, int collectIntervalSeconds) {
        this.id = UuidV7.generate();
        this.code = code;
        this.name = name;
        this.collectIntervalSeconds = collectIntervalSeconds;
        this.enabled = false;
        this.consecutiveFailureCount = 0;
    }

    /**
     * 채널을 등록한다. 인증정보가 등록되기 전이므로 비활성 상태로 시작한다.
     */
    public static Channel register(ChannelCode code, int collectIntervalSeconds) {
        if (collectIntervalSeconds <= 0) {
            throw new BusinessException(ChannelErrorCode.INVALID_COLLECT_INTERVAL, collectIntervalSeconds);
        }
        return new Channel(code, code.displayName(), collectIntervalSeconds);
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public void changeCollectInterval(int seconds) {
        if (seconds <= 0) {
            throw new BusinessException(ChannelErrorCode.INVALID_COLLECT_INTERVAL, seconds);
        }
        this.collectIntervalSeconds = seconds;
    }

    /** 수집 성공. 커서를 전진시키고 실패 카운터를 초기화한다. */
    public void recordCollectSuccess(Instant collectedUntil, Instant collectedAt) {
        this.collectedUntil = collectedUntil;
        this.lastCollectedAt = collectedAt;
        this.lastCollectStatus = CollectStatus.SUCCESS;
        this.lastCollectError = null;
        this.consecutiveFailureCount = 0;
    }

    /** 수집 실패. 커서는 전진시키지 않는다 — 다음 시도에서 같은 구간을 다시 조회해야 한다. */
    public void recordCollectFailure(Instant attemptedAt, String error) {
        this.lastCollectedAt = attemptedAt;
        this.lastCollectStatus = CollectStatus.FAILED;
        this.lastCollectError = error;
        this.consecutiveFailureCount++;
    }

    public boolean hasReachedFailureThreshold(int threshold) {
        return consecutiveFailureCount >= threshold;
    }

    /** 수집 시각이 도래했는지. */
    public boolean isDueAt(Instant now) {
        if (!enabled) {
            return false;
        }
        if (lastCollectedAt == null) {
            return true;
        }
        return !now.isBefore(lastCollectedAt.plusSeconds(collectIntervalSeconds));
    }
}
