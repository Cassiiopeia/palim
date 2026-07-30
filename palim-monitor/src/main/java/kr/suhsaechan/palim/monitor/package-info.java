/**
 * 감시 계층 — 내부 상태를 점검해 이상을 알린다.
 *
 * <p>도메인 모듈이 아니라 <b>여러 도메인을 관통하는 조율 계층</b>이다. {@code palim-collector} 와
 * 같은 위치이지만 방향이 반대다.
 *
 * <table border="1">
 *   <caption>조율 계층 비교</caption>
 *   <tr><th>계층</th><th>책임</th><th>방향</th><th>실패 시</th></tr>
 *   <tr><td>{@code palim-collector}</td><td>채널 주문을 내부 상태에 반영</td><td>외부 → 내부</td>
 *       <td>커서를 되돌려 재시도</td></tr>
 *   <tr><td>{@code palim-monitor}</td><td>내부 상태를 점검해 알림</td><td>내부 → 알림</td>
 *       <td>다음 주기에 다시 본다</td></tr>
 * </table>
 *
 * <h2>여기 있는 배치가 없으면 벌어지는 일</h2>
 *
 * <ul>
 *   <li>{@code StockConsistencyChecker} 없으면 — 재고 기준값이 틀어진 것을 <b>아무도 모른다.</b>
 *       본 시스템은 스스로를 "재고의 유일한 기준"으로 정의하므로, 자기 검산이 없으면 틀어진
 *       상태로 장기간 운영된다
 *   <li>{@code LowStockMonitor} 없으면 — 품절 직전 상황을 발주자가 놓친다(F-05)
 *   <li>{@code DailyReportScheduler} 없으면 — 전일 실적을 확인할 경로가 없다(F-06)
 * </ul>
 *
 * <h2>알림 등록 시 재발송 억제를 반드시 쓴다</h2>
 *
 * <p>감시 배치는 주기적으로 <b>같은 상태를 반복 발견</b>한다. 재고가 계속 부족하면 매 주기마다
 * 알림을 등록하게 되는데, 그러면 발주자가 알림을 아예 보지 않게 되어 이 시스템의 존재 이유가
 * 무너진다. {@code OutboxService.enqueueIfNotRecent} 를 쓴다.
 */
package kr.suhsaechan.palim.monitor;
