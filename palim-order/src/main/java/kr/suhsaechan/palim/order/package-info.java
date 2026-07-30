/**
 * 주문 도메인.
 *
 * <p>수집된 주문과 주문 항목을 소유한다. 주문은 삭제하지 않으며, 취소·반품은 상태 전이로
 * 처리한다(F-03).
 *
 * <p>중복 수집 방지는 {@code (channel_code, channel_order_no, channel_line_no)} 유니크 제약으로
 * 보장한다. "조회 후 없으면 삽입"은 수집이 중첩되는 순간 뚫리므로, 삽입 성공 여부를 판정
 * 기준으로 삼는다(A-02).
 *
 * <p>SKU를 참조할 때는 {@code UUID skuId} 값만 갖는다. {@code palim-sku}를 의존하지 않는다.
 * 미매핑 주문도 저장해야 하므로 이 값은 nullable이다(F-04).
 */
package kr.suhsaechan.palim.order;
