package kr.suhsaechan.palim.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 도메인 규칙 단위 테스트. Spring 컨텍스트를 띄우지 않는다(설계서 8장).
 */
class AuditLogTest {

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        void 내용이_비면_유형의_기본_문장을_쓴다() {
            AuditLog log = AuditLog.of(AuditRecord.of(AuditType.LOGIN_SUCCESS)
                    .actor("admin", null)
                    .build());

            assertThat(log.getSummary()).isEqualTo("로그인했습니다.(인증 성공)");
        }

        @Test
        void 내용이_있으면_그대로_쓴다() {
            AuditLog log = AuditLog.of(AuditRecord.of(AuditType.LOGOUT_DUPLICATE)
                    .summary("로그아웃했습니다.(로그인 중복 — 접속 10.0.0.1 강제 종료)")
                    .build());

            assertThat(log.getSummary()).contains("10.0.0.1");
        }

        @Test
        void 유형이_없으면_거부한다() {
            assertThatThrownBy(() -> new AuditRecord(Instant.now(), null, null, null, null,
                    null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 시각을_넘기지_않으면_현재_시각이_들어간다() {
            AuditLog log = AuditLog.of(AuditRecord.of(AuditType.VIEW).build());

            assertThat(log.getOccurredAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("길이 초과")
    class Truncation {

        @Test
        void 외부가_정하는_값은_잘려서라도_남는다() {
            // User-Agent 는 외부가 길이를 정한다. 그대로 넣으면 INSERT 가 실패해 기록이 통째로
            // 유실되므로, 잘라서 남긴다.
            String longUserAgent = "Mozilla/5.0 ".repeat(100);

            AuditLog log = AuditLog.of(AuditRecord.of(AuditType.VIEW)
                    .request("/skus", longUserAgent)
                    .build());

            assertThat(log.getUserAgent()).hasSize(300);
        }

        @Test
        void 아이디도_길면_잘라서_남긴다() {
            // 로그인 실패 기록에는 입력된 아이디가 그대로 들어온다. 공격자가 긴 값을 넣어
            // 기록 자체를 실패시키는 것을 막는다.
            AuditLog log = AuditLog.of(AuditRecord.of(AuditType.LOGIN_FAILURE)
                    .actor("a".repeat(200), null)
                    .build());

            assertThat(log.getActorId()).hasSize(50);
        }
    }

    @Nested
    @DisplayName("검색 조건")
    class SearchCondition {

        @Test
        void 기간이_없으면_거부한다() {
            // 기간 없는 전체 조회는 가장 빠르게 쌓이는 테이블의 전체 스캔이다.
            assertThatThrownBy(() -> new AuditSearchCondition(null, Instant.now(), null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 종료가_시작보다_앞서면_거부한다() {
            Instant now = Instant.now();
            assertThatThrownBy(() -> new AuditSearchCondition(
                    now, now.minusSeconds(60), null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 빈_검색어는_키워드_없음으로_다룬다() {
            Instant now = Instant.now();
            AuditSearchCondition condition = new AuditSearchCondition(
                    now.minusSeconds(60), now, null, AuditSearchField.ACTOR_ID, "   ");

            assertThat(condition.hasKeyword()).isFalse();
        }
    }
}
