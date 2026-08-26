package kr.suhsaechan.palim.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 도메인 규칙 단위 테스트. Spring 컨텍스트를 띄우지 않는다(설계서 8장).
 */
class NotificationOutboxTest {

    private static final int MAX_ATTEMPTS = 5;

    private static NotificationOutbox failed() {
        NotificationOutbox outbox = NotificationOutbox.enqueue(NotificationType.NEW_ORDER, NotificationChannel.TELEGRAM, "{}");
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            outbox.markAttemptFailed("타임아웃", MAX_ATTEMPTS);
        }
        return outbox;
    }

    @Nested
    @DisplayName("수동 재발송")
    class RetryManually {

        @Test
        void 실패_상태는_대기로_돌아가고_시도_횟수가_초기화된다() {
            NotificationOutbox outbox = failed();

            outbox.retryManually();

            assertThat(outbox.isPending()).isTrue();
            // 초기화하지 않으면 한도(5회)를 이미 소진한 상태라 재발송 첫 실패에 즉시 다시
            // FAILED 가 된다 — 재발송이 사실상 1회짜리가 된다.
            assertThat(outbox.getAttemptCount()).isZero();
        }

        @Test
        void 원인_문맥을_위해_마지막_오류는_남긴다() {
            NotificationOutbox outbox = failed();

            outbox.retryManually();

            assertThat(outbox.getLastError()).isEqualTo("타임아웃");
        }

        @Test
        void 대기_상태는_거부한다() {
            NotificationOutbox outbox = NotificationOutbox.enqueue(NotificationType.NEW_ORDER, NotificationChannel.TELEGRAM, "{}");

            assertThatThrownBy(outbox::retryManually)
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.NOTIFICATION_NOT_RETRYABLE);
        }

        @Test
        @DisplayName("발송된 알림은 거부한다 — 되돌리면 같은 알림이 중복 발송된다")
        void 발송_상태_거부() {
            NotificationOutbox outbox = NotificationOutbox.enqueue(NotificationType.NEW_ORDER, NotificationChannel.TELEGRAM, "{}");
            outbox.markSent(Instant.now());

            assertThatThrownBy(outbox::retryManually)
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.NOTIFICATION_NOT_RETRYABLE);
        }
    }

    @Nested
    @DisplayName("실패 누적")
    class FailureAccumulation {

        @Test
        void 한도_미만은_대기로_남아_재시도된다() {
            NotificationOutbox outbox = NotificationOutbox.enqueue(NotificationType.NEW_ORDER, NotificationChannel.TELEGRAM, "{}");

            outbox.markAttemptFailed("타임아웃", MAX_ATTEMPTS);

            assertThat(outbox.isPending()).isTrue();
            assertThat(outbox.getAttemptCount()).isEqualTo(1);
        }

        @Test
        void 한도_도달_시_실패로_전환된다() {
            assertThat(failed().isFailed()).isTrue();
        }
    }
}
