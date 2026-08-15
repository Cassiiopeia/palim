package kr.suhsaechan.palim.notification.payload;

import java.time.Instant;

/**
 * 대조가 여러 날 연속으로 돌지 못했다는 알림 내용.
 *
 * <p>하루 못 돈 것은 알리지 않는다 — 수집이 늦으면 기준 시각이 어긋나고 다음 회차에 저절로
 * 풀린다. 문제는 <b>영영 안 풀리는 실패도 똑같이 생겼다</b>는 것이다. 지금까지 자동 대조는
 * 실패하면 로그만 남기고 넘어갔고, 그래서 몇 주를 안 돌아도 아무도 몰랐다.
 *
 * <p>「며칠째인가」 를 담는 이유는 <b>사람이 심각도를 스스로 판단하게</b> 하기 위해서다.
 * 사흘째와 서른 날째는 같은 문장이어도 뜻이 전혀 다르다. 회차가 아니라 날을 세는 이유는
 * 실행 이력이 스케줄러만의 것이 아니기 때문이다 — 사람이 화면에서 몇 번 눌러 본 것이
 * 「사흘째」 로 둔갑하면 안 된다.
 *
 * @param definition  대조 이름
 * @param reason      마지막 실패 사유. 이미 사람 말로 풀린 문장이 들어온다
 * @param failedDays  연속 실패한 날 수
 * @param lastTriedAt 마지막으로 시도한 시각. <b>표시 직전에만</b> 지역 시각으로 바꾼다
 */
public record ReconcileBlockedPayload(
        String definition,
        String reason,
        int failedDays,
        Instant lastTriedAt
) {
}
