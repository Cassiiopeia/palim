/**
 * 알림 도메인.
 *
 * <p>Outbox, 알림 설정, 텔레그램 발송을 소유한다.
 *
 * <p>발송 대상은 PostgreSQL Outbox에 먼저 기록한 뒤 큐에 투입한다. 주문 저장과 Outbox
 * 삽입이 같은 트랜잭션이므로, RabbitMQ가 중단돼도 Outbox 행이 남아 재기동 시 이어서
 * 발송된다(A-14). Outbox 없이 큐만 쓰면 주문 커밋 후 발행이 실패하는 순간 알림이 영구
 * 소실된다.
 *
 * <p>텔레그램 발송은 별도 모듈로 분리하지 않는다. Outbox를 소유한 이 도메인의 외부 연동
 * 인프라이기 때문이다.
 */
package kr.suhsaechan.palim.notification;
