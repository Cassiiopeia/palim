package kr.suhsaechan.palim.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채널 재고 전송 안전장치 (F-08). 단일 행으로 관리한다.
 *
 * <p>재고 계산 오류로 0을 전송하면 해당 상품이 전 채널에서 품절 처리되어 매출 손실이 발생한다.
 * 시뮬레이션 모드와 변동량 상한은 이런 사고를 예방하기 위한 필수 장치다.
 *
 * <p>도입 초기에는 {@code simulationMode = true} 로 수 주간 운영하며 전송 예정 내용을 검증한 뒤
 * 실제 전송으로 전환한다. 이는 선택이 아니라 필수 절차로 취급한다(설계서 10.2).
 */
@Getter
@Entity
@Table(name = "stock_push_setting")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockPushSetting extends BaseTimeEntity {

    private static final int DEFAULT_MAX_DELTA_PER_PUSH = 50;

    @Id
    private UUID id;

    /** 전체 중단 스위치. 웹에서 즉시 비활성화할 수 있어야 한다. */
    @Column(nullable = false)
    private boolean enabled;

    /** 실제 전송 없이 전송 예정 내용만 알린다. */
    @Column(nullable = false)
    private boolean simulationMode;

    /** 1회 변경량 상한. 초과하면 전송을 중단하고 경고를 보낸다. */
    @Column(nullable = false)
    private int maxDeltaPerPush;

    @Version
    private Long version;

    private StockPushSetting(boolean enabled, boolean simulationMode, int maxDeltaPerPush) {
        this.id = UuidV7.generate();
        this.enabled = enabled;
        this.simulationMode = simulationMode;
        this.maxDeltaPerPush = maxDeltaPerPush;
    }

    /**
     * 초기 설정.
     *
     * <p>전송은 비활성, 시뮬레이션은 활성으로 시작한다. 안전한 쪽이 기본값이어야 한다.
     */
    public static StockPushSetting createDefault() {
        return new StockPushSetting(false, true, DEFAULT_MAX_DELTA_PER_PUSH);
    }

    public void enable() {
        this.enabled = true;
    }

    /** 전체 중단. 사고 발생 시 가장 먼저 눌러야 하는 스위치다. */
    public void disable() {
        this.enabled = false;
    }

    public void turnOnSimulation() {
        this.simulationMode = true;
    }

    public void turnOffSimulation() {
        this.simulationMode = false;
    }

    public void changeMaxDeltaPerPush(int maxDelta) {
        if (maxDelta <= 0) {
            throw new IllegalArgumentException("변동량 상한은 1 이상이어야 합니다: " + maxDelta);
        }
        this.maxDeltaPerPush = maxDelta;
    }

    /** 전송 가능 여부. 상한을 넘으면 차단한다. */
    public boolean allowsDelta(int beforeQuantity, int afterQuantity) {
        return Math.abs(afterQuantity - beforeQuantity) <= maxDeltaPerPush;
    }
}
