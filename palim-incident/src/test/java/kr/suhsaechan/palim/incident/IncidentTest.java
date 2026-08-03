package kr.suhsaechan.palim.incident;

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
class IncidentTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

    private static Incident open() {
        return Incident.open(IncidentType.OVERSELL, "SKU-001 초과판매 — 재고 -2",
                "상세", "OVERSELL:SKU-001", NOW);
    }

    @Nested
    @DisplayName("재발 누적")
    class Recurrence {

        @Test
        void 재발하면_횟수가_늘고_제목이_최신으로_갱신된다() {
            Incident incident = open();

            incident.recordRecurrence("SKU-001 초과판매 — 재고 -5", "상세2", NOW.plusSeconds(60));

            assertThat(incident.getOccurrenceCount()).isEqualTo(2);
            // 목록에는 악화된 현재 상태(-5)가 보여야 한다.
            assertThat(incident.getTitle()).contains("-5");
            assertThat(incident.getLastOccurredAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(incident.getFirstOccurredAt()).isEqualTo(NOW);
        }

        @Test
        void 해결된_인시던트에는_누적할_수_없다() {
            Incident incident = open();
            incident.resolve("재고 확보", NOW);

            assertThatThrownBy(() -> incident.recordRecurrence("t", "d", NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INCIDENT_ALREADY_RESOLVED);
        }
    }

    @Nested
    @DisplayName("상태 전이")
    class Transition {

        @Test
        void 미확인에서_확인으로() {
            Incident incident = open();

            incident.acknowledge(NOW);

            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
            assertThat(incident.getAcknowledgedAt()).isEqualTo(NOW);
        }

        @Test
        void 확인_상태를_다시_확인할_수_없다() {
            Incident incident = open();
            incident.acknowledge(NOW);

            assertThatThrownBy(() -> incident.acknowledge(NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INCIDENT_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("미확인에서 바로 해결할 수 있다 — 사소한 건을 두 번 클릭시킬 이유가 없다")
        void 직행_해결() {
            Incident incident = open();

            incident.resolve("재고 확보 후 출고 완료", NOW);

            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(incident.getResolutionMemo()).isEqualTo("재고 확보 후 출고 완료");
        }

        @Test
        @DisplayName("메모 없는 해결은 거부한다 — 무엇을 했는지 없는 해결은 추적이 아니다")
        void 메모_필수() {
            Incident incident = open();

            assertThatThrownBy(() -> incident.resolve("  ", NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INCIDENT_MEMO_REQUIRED);
        }

        @Test
        void 해결은_최종_상태다() {
            Incident incident = open();
            incident.resolve("조치", NOW);

            assertThatThrownBy(() -> incident.resolve("또 조치", NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INCIDENT_ALREADY_RESOLVED);
        }
    }
}
