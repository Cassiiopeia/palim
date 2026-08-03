/**
 * 인시던트 도메인 — 발주자 조치가 필요한 사건의 처리 상태 추적 (#34).
 *
 * <p>알림({@code palim-notification})은 "알리는 것"이고 이 모듈은 "처리를 추적하는 것"이다.
 * 알림은 흘러가면 끝이지만 인시던트는 미확인 → 확인 → 해결 상태를 갖는다.
 *
 * <h2>다른 도메인을 의존하지 않는다</h2>
 *
 * <p>모든 조율 계층(collector·monitor)이 사건을 보고하므로, 특정 도메인을 참조하면 순환이
 * 생긴다. 대상은 {@code dedupeKey} 문자열과 표시 문자열로만 받는다.
 */
package kr.suhsaechan.palim.incident;
