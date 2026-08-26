package kr.suhsaechan.palim.notification.delivery;

/**
 * 메일 한 통.
 *
 * <p>메일에는 <b>제목</b>이라는 자리가 있는데 지금 발송 경로에는 그 개념이 없었다 — 메신저는
 * 첫 줄이 제목 노릇을 한다. 요약 알림의 존재 이유가 「열지 않아도 판단」 이므로 제목을 값으로
 * 다룬다.
 */
public record MailMessage(String subject, String body) {
}
