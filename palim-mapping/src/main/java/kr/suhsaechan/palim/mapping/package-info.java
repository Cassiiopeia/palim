/**
 * 상품 매핑 도메인.
 *
 * <p>채널별 상품코드와 자사 SKU 식별자의 연결을 소유한다. 매핑되지 않은 상품의 주문은
 * 재고에 반영되지 않으므로, 매핑은 시스템 동작의 필수 전제다(F-04).
 *
 * <p>채널 코드와 SKU 식별자를 모두 {@code UUID}·문자열 값으로만 다룬다.
 * {@code palim-channel}·{@code palim-sku}를 의존하지 않는다.
 */
package kr.suhsaechan.palim.mapping;
