package kr.suhsaechan.palim.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상태 전이 규칙 검증. Spring 없는 단위 테스트다(설계서 8장).
 *
 * <p>핵심은 두 가지다 — 재발 누적이 상태를 건드리지 않는 것, RESOLVED 가 최종 상태인 것.
 * 이 규칙이 깨지면 확인 표시가 무의미해지거나 해결 이력이 덮어써진다.
 */
class IncidentTest {

    private static final Instant OCCURRED = Instant.parse("2026-08-03T00:00:00Z");

    private Incident openIncident() {
        return Incident.open(IncidentType.OVERSELL, "OVERSELL:SKU-1",
                "SKU-1 청바지 초과판매", "재고 -3", OCCURRED);
    }

    @Test
    @DisplayName("생성 직후는 미확인 상태이고 발생 횟수는 1이다")
    void 생성_직후_상태() {
        Incident incident = openIncident();

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getOccurrenceCount()).isEqualTo(1);
        assertThat(incident.getLastOccurredAt()).isEqualTo(OCCURRED);
        assertThat(incident.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("재발 누적은 횟수·최근 발생·상세만 갱신하고 상태는 건드리지 않는다")
    void 재발_누적() {
        Incident incident = openIncident();
        incident.acknowledge();
        Instant later = OCCURRED.plusSeconds(3600);

        incident.recordRecurrence("재고 -5", later);

        assertThat(incident.getOccurrenceCount()).isEqualTo(2);
        assertThat(incident.getLastOccurredAt()).isEqualTo(later);
        assertThat(incident.getDetail()).isEqualTo("재고 -5");
        // 이미 인지한 문제다 — 미확인으로 되돌리면 확인 표시가 무의미해진다.
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
    }

    @Test
    @DisplayName("미확인 → 확인 전이가 확인 시각을 남긴다")
    void 확인_전이() {
        Incident incident = openIncident();

        incident.acknowledge();

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(incident.getAcknowledgedAt()).isNotNull();
    }

    @Test
    @DisplayName("확인 상태에서 다시 확인하면 거부된다")
    void 중복_확인_거부() {
        Incident incident = openIncident();
        incident.acknowledge();

        assertThatThrownBy(incident::acknowledge)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INCIDENT_STATUS_INVALID);
    }

    @Test
    @DisplayName("미확인에서 바로 해결할 수 있다 — 1인 운영의 클릭 수고를 줄인다")
    void 미확인에서_직행_해결() {
        Incident incident = openIncident();

        incident.resolve("실사 조정으로 맞춤");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.getResolvedAt()).isNotNull();
        assertThat(incident.getResolutionNote()).isEqualTo("실사 조정으로 맞춤");
    }

    @Test
    @DisplayName("확인 상태에서도 해결할 수 있고 메모는 없어도 된다")
    void 확인에서_해결() {
        Incident incident = openIncident();
        incident.acknowledge();

        incident.resolve(null);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.getResolutionNote()).isNull();
    }

    @Test
    @DisplayName("해결된 건을 다시 해결하면 거부된다 — 해결 이력을 덮어쓰지 않는다")
    void 중복_해결_거부() {
        Incident incident = openIncident();
        incident.resolve("조치 완료");

        assertThatThrownBy(() -> incident.resolve("다른 메모"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INCIDENT_STATUS_INVALID);
    }

    @Test
    @DisplayName("해결된 건은 확인으로 되돌릴 수 없다")
    void 해결_후_확인_거부() {
        Incident incident = openIncident();
        incident.resolve(null);

        assertThatThrownBy(incident::acknowledge)
                .isInstanceOf(BusinessException.class);
    }
}
