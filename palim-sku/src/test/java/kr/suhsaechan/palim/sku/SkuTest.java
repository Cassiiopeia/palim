package kr.suhsaechan.palim.sku;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 도메인 규칙 단위 테스트. Spring 컨텍스트를 띄우지 않는다(설계서 8장).
 */
class SkuTest {

    private static Sku sku(int quantity, int threshold) {
        return Sku.register("SKU-001", "테스트 상품", quantity, threshold);
    }

    @Nested
    @DisplayName("등록")
    class Register {

        @Test
        void 초기_재고가_음수면_거부한다() {
            assertThatThrownBy(() -> Sku.register("SKU-001", "상품", -1, 5))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_STOCK_AMOUNT);
        }

        @Test
        void 안전재고_임계치가_음수면_거부한다() {
            assertThatThrownBy(() -> Sku.register("SKU-001", "상품", 10, -1))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_SAFETY_THRESHOLD);
        }

        @Test
        void 등록_시_식별자가_이미_확정된다() {
            assertThat(sku(10, 5).getId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("재고 차감")
    class Decrease {

        @Test
        void 재고를_차감한다() {
            Sku sku = sku(10, 5);

            sku.decrease(3);

            assertThat(sku.getQuantity()).isEqualTo(7);
        }

        @Test
        void 수동_차감은_재고보다_많으면_거부한다() {
            Sku sku = sku(2, 5);

            assertThatThrownBy(() -> sku.decrease(3))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        }

        /**
         * 판매 차감은 음수를 허용한다. 주문 저장을 실패시키면 데이터가 유실되고,
         * 재고를 반영하지 않으면 소급 반영 대상으로 무한 재시도되기 때문이다.
         */
        @Test
        void 판매_차감은_음수_재고를_허용한다() {
            Sku sku = sku(2, 5);

            boolean oversold = sku.decreaseForSale(3);

            assertThat(oversold).isTrue();
            assertThat(sku.getQuantity()).isEqualTo(-1);
            assertThat(sku.isOversold()).isTrue();
            assertThat(sku.isOutOfStock()).isTrue();
        }

        @Test
        void 재고를_정확히_0까지는_차감할_수_있다() {
            Sku sku = sku(3, 5);

            sku.decrease(3);

            assertThat(sku.getQuantity()).isZero();
            assertThat(sku.isOutOfStock()).isTrue();
        }

        @Test
        void 차감_수량이_0_이하면_거부한다() {
            Sku sku = sku(10, 5);

            assertThatThrownBy(() -> sku.decrease(0)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> sku.decrease(-1)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> sku.decreaseForSale(0)).isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("안전재고 판정")
    class Threshold {

        @Test
        void 임계치_미만이면_부족으로_본다() {
            assertThat(sku(4, 5).isBelowThreshold()).isTrue();
        }

        @Test
        void 임계치와_같으면_부족이_아니다() {
            assertThat(sku(5, 5).isBelowThreshold()).isFalse();
        }
    }

    @Nested
    @DisplayName("실사 조정")
    class Adjust {

        @Test
        void 절대값으로_덮어쓴다() {
            Sku sku = sku(10, 5);

            sku.adjustTo(50);

            assertThat(sku.getQuantity()).isEqualTo(50);
        }

        @Test
        void 음수로_조정하면_거부한다() {
            Sku sku = sku(10, 5);

            assertThatThrownBy(() -> sku.adjustTo(-1)).isInstanceOf(BusinessException.class);
        }
    }
}
