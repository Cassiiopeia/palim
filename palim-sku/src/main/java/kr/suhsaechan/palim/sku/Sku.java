package kr.suhsaechan.palim.sku;

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
            throw new IllegalArgumentException("초기 재고는 0 이상이어야 합니다: " + initialQuantity);
        }
        if (safetyThreshold < 0) {
            throw new IllegalArgumentException("안전재고 임계치는 0 이상이어야 합니다: " + safetyThreshold);
        }
        return new Sku(code, name, initialQuantity, safetyThreshold);
    }

    /**
     * 재고를 차감한다.
     *
     * <p>호출자는 비관적 락으로 이 엔티티를 조회한 상태여야 한다. 트랜잭션은
     * {@code palim-collector}가 연다(설계서 3.4).
     */
    public void decrease(int amount) {
        requirePositive(amount);
        if (quantity - amount < 0) {
            throw new InsufficientStockException(code, quantity, amount);
        }
        this.quantity -= amount;
    }

    public void increase(int amount) {
        requirePositive(amount);
        this.quantity += amount;
    }

    /** 실사 조정. 차감·증가와 달리 절대값으로 덮어쓴다. */
    public void adjustTo(int newQuantity) {
        if (newQuantity < 0) {
            throw new IllegalArgumentException("조정 후 재고는 0 이상이어야 합니다: " + newQuantity);
        }
        this.quantity = newQuantity;
    }

    public void changeSafetyThreshold(int threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException("안전재고 임계치는 0 이상이어야 합니다: " + threshold);
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
        return quantity == 0;
    }

    private static void requirePositive(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("변동 수량은 1 이상이어야 합니다: " + amount);
        }
    }
}
