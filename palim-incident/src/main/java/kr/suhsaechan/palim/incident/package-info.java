/**
 * 인시던트 도메인 (#35).
 *
 * <p>오버셀 · 재고 정합성 불일치 · 미매핑 상품을 미확인 → 확인 → 해결 상태로 관리한다.
 * 텔레그램 알림은 도달하면 끝이라 "확인했는지, 조치했는지"가 남지 않는다 — 인시던트는
 * 그 공백을 메우는, 사람이 마감하는 장부다.
 *
 * <p><b>다른 도메인 모듈을 의존하지 않는다.</b> 대상 식별은 {@code dedupeKey} 문자열과
 * 표시용 {@code title}·{@code detail} 로만 표현한다. 인시던트 생성은 조율 계층
 * (palim-collector · palim-monitor)이 감지 트랜잭션 안에서 수행한다.
 */
package kr.suhsaechan.palim.incident;
