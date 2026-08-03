package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.incident.Incident;
import kr.suhsaechan.palim.incident.IncidentRepository;
import kr.suhsaechan.palim.incident.IncidentService;
import kr.suhsaechan.palim.incident.IncidentStatus;
import kr.suhsaechan.palim.incident.IncidentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 인시던트 중복 방지·해결 후 재발 통합 검증.
 *
 * <p>{@code report()} 가 {@code REQUIRES_NEW} 라 테스트 트랜잭션으로 롤백되지 않는다 —
 * 클래스에 {@code @Transactional} 을 붙이지 않고 테스트가 직접 정리한다.
 */
class IncidentServiceIntegrationTest extends IntegrationTest {

    private static final String KEY = "OVERSELL:IT-SKU-999";

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanUp() {
        incidentRepository.deleteAll(
                incidentRepository.findAll().stream()
                        .filter(incident -> KEY.equals(incident.getDedupeKey()))
                        .toList());
    }

    private List<Incident> findByKey() {
        return incidentRepository.findAll().stream()
                .filter(incident -> KEY.equals(incident.getDedupeKey()))
                .toList();
    }

    @Test
    @DisplayName("같은 키의 재발은 새 행이 아니라 발생 횟수로 누적된다")
    void 중복_방지() {
        incidentService.report(IncidentType.OVERSELL, KEY, "재고 -1", "상세1");
        incidentService.report(IncidentType.OVERSELL, KEY, "재고 -3", "상세2");

        List<Incident> incidents = findByKey();
        assertThat(incidents).hasSize(1);
        assertThat(incidents.getFirst().getOccurrenceCount()).isEqualTo(2);
        // 목록에는 악화된 최신 상태가 보여야 한다.
        assertThat(incidents.getFirst().getTitle()).isEqualTo("재고 -3");
    }

    @Test
    @DisplayName("해결된 뒤 재발하면 새 인시던트다 — 해결 이력을 덮어쓰지 않는다")
    void 해결_후_재발() {
        incidentService.report(IncidentType.OVERSELL, KEY, "재고 -1", "상세");

        // 확인·해결은 MANDATORY 라 트랜잭션을 열어 호출한다(조율 계층 역할).
        transactionTemplate.executeWithoutResult(tx -> {
            Incident first = findByKey().getFirst();
            incidentService.resolve(first.getId(), "재고 확보");
        });

        incidentService.report(IncidentType.OVERSELL, KEY, "재고 -2 (재발)", "상세");

        List<Incident> incidents = findByKey();
        assertThat(incidents).hasSize(2);
        assertThat(incidents).filteredOn(i -> i.getStatus() == IncidentStatus.RESOLVED)
                .singleElement()
                .satisfies(resolved -> assertThat(resolved.getResolutionMemo()).isEqualTo("재고 확보"));
        assertThat(incidents).filteredOn(Incident::isUnresolved)
                .singleElement()
                .satisfies(reopened -> assertThat(reopened.getOccurrenceCount()).isEqualTo(1));
    }
}
