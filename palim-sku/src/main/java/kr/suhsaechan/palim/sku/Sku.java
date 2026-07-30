package kr.suhsaechan.palim.sku;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SKU와 현재 재고 스냅샷.
 *
 * <p>어느 채널에서 판매되든 이 수량에서 차감된다. 본 시스템의 데이터베이스가 재고의 유일한
 * 기준이며 이카운트 ERP는 회계·세무 목적으로만 사용한다(F-03).
 *
 * <p>{@code quantity}는 스냅샷이고 실제 변동 근거는 {@link StockMovement}에 남는다. 두 값이
 * 어긋나는지는 일 1회 대조 배치가 검산한다(설계서 5.3).
 */
@Getter
@Entity
@Table(name = "sku")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sku extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int safetyThreshold;

    /** 단종 여부. 소프트 삭제가 아니다 — 재고 이력이 참조하므로 물리 삭제할 수 없다. */
    @Column(nullable = false)
    private boolean active = true;

    @Version
    private Long version;

    private Sku(String code, String name, int quantity, int safetyThreshold) {
        this.id = UuidV7.generate();
        this.code = code;
        this.name = name;
        this.quantity = quantity;
        this.safetyThreshold = safetyThreshold;
    }

    public static Sku register(String code, String name, int initialQuantity, int safetyThreshold) {
        if (initialQuantity < 0) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_AMOUNT, initialQuantity);
        }
        if (safetyThreshold < 0) {
            throw new BusinessException(ErrorCode.INVALID_SAFETY_THRESHOLD, safetyThreshold);
        }
        return new Sku(code, name, initialQuantity, safetyThreshold);
    }

    /**
     * 수동 차감. 재고보다 많이 차감하려 하면 거부한다.
     *
     * <p>폐기·분실처럼 사람이 입력하는 경로에 쓴다. 실재고보다 많은 수량을 입력하는 것은
     * 입력 실수일 가능성이 높으므로 막는다. 판매 차감은 {@link #decreaseForSale} 을 쓴다.
     *
     * <p>호출자는 비관적 락으로 이 엔티티를 조회한 상태여야 한다. 트랜잭션은
     * {@code palim-collector}가 연다(설계서 3.4).
     */
    public void decrease(int amount) {
        requirePositive(amount);
        if (quantity - amount < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, code, quantity, amount);
        }
        this.quantity -= amount;
    }

    /**
     * 판매 차감. <b>음수 재고를 허용한다.</b>
     *
     * <p>채널 재고 동기화(F-08)는 즉시 반영이 아니라 수 분 내 반영이므로, 그 사이 실재고를
     * 초과하는 주문이 접수될 수 있다. 이때 선택지는 셋이다.
     *
     * <ul>
     *   <li>주문 저장을 실패시킨다 → <b>데이터 유실.</b> 고객은 이미 결제했고 채널에는 주문이 있다
     *   <li>주문만 저장하고 재고를 반영하지 않는다 → {@code stockApplied = false} 로 남아
     *       소급 반영 대상으로 무한 재시도된다
     *   <li>음수를 허용한다 → 사실을 정확히 표현하고 대조 배치(설계서 5.3)가 계속 성립한다
     * </ul>
     *
     * <p>세 번째를 택했다. 재고 시스템에서 음수는 "출고해야 할 빚"을 표현하는 정상적인 상태이며,
     * 화면과 알림으로 발주자가 즉시 인지할 수 있다.
     *
     * @return 이 차감으로 재고가 음수가 되었는지 여부. true 면 오버셀링 알림 대상이다
     */
    public boolean decreaseForSale(int amount) {
        requirePositive(amount);
        this.quantity -= amount;
        return quantity < 0;
    }

    public void increase(int amount) {
        requirePositive(amount);
        this.quantity += amount;
    }

    /** 실사 조정. 차감·증가와 달리 절대값으로 덮어쓴다. */
    public void adjustTo(int newQuantity) {
        if (newQuantity < 0) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_AMOUNT, newQuantity);
        }
        this.quantity = newQuantity;
    }

    public void changeSafetyThreshold(int threshold) {
        if (threshold < 0) {
            throw new BusinessException(ErrorCode.INVALID_SAFETY_THRESHOLD, threshold);
        }
        this.safetyThreshold = threshold;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void discontinue() {
        this.active = false;
    }

    public void resume() {
        this.active = true;
    }

    /** 안전재고 미달 여부 (F-05). */
    public boolean isBelowThreshold() {
        return quantity < safetyThreshold;
    }

    public boolean isOutOfStock() {
        return quantity <= 0;
    }

    /** 오버셀링 상태 — 출고해야 할 수량이 실재고를 초과한다. */
    public boolean isOversold() {
        return quantity < 0;
    }

    private static void requirePositive(int amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_AMOUNT, amount);
        }
    }
}
