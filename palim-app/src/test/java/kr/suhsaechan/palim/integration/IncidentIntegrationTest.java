package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.incident.Incident;
import kr.suhsaechan.palim.incident.IncidentRepository;
import kr.suhsaechan.palim.incident.IncidentService;
import kr.suhsaechan.palim.incident.IncidentStatus;
import kr.suhsaechan.palim.incident.IncidentType;
import kr.suhsaechan.palim.monitor.StockConsistencyChecker;
import kr.suhsaechan.palim.sku.Sku;
import kr.suhsaechan.palim.sku.SkuService;
import kr.suhsaechan.palim.sku.StockMovementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인시던트 생성·누적·마감 검증 (#35).
 *
 * <p>핵심은 두 가지다 — 미해결 상태의 같은 문제가 행을 늘리지 않는 것(부분 유니크 인덱스가
 * 최종 방어선), 그리고 감시 배치의 알림 억제와 무관하게 발생 횟수가 누적되는 것. 부분
 * 유니크 인덱스는 인메모리 DB 와 동작이 달라 실제 PostgreSQL 로만 검증할 수 있다.
 */
@Transactional
class IncidentIntegrationTest extends IntegrationTest {

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private SkuService skuService;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private StockConsistencyChecker stockConsistencyChecker;

    private Incident report(String dedupeKey) {
        return incidentService.report(IncidentType.OVERSELL, dedupeKey,
                "SKU 초과판매", "재고 -1");
    }

    // ------------------------------------------------------------------
    // 생성 · 누적
    // ------------------------------------------------------------------

    @Test
    @DisplayName("같은 키의 미해결 건은 행이 늘지 않고 횟수만 누적된다")
    void 미해결_건은_누적된다() {
        report("OVERSELL:INC-ACC");
        Incident second = report("OVERSELL:INC-ACC");

        assertThat(second.getOccurrenceCount()).isEqualTo(2);
        assertThat(incidentRepository.findAll())
                .filteredOn(i -> "OVERSELL:INC-ACC".equals(i.getDedupeKey()))
                .hasSize(1);
    }

    @Test
    @DisplayName("확인 상태에서 재발해도 미확인으로 돌아가지 않는다")
    void 확인_상태_재발은_상태를_유지한다() {
        Incident incident = report("OVERSELL:INC-ACK");
        incidentService.acknowledge(incident.getId());

        Incident recurred = report("OVERSELL:INC-ACK");

        assertThat(recurred.getId()).isEqualTo(incident.getId());
        assertThat(recurred.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(recurred.getOccurrenceCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("해결 후 재발은 새 인시던트가 된다 — 해결 이력을 보존한다")
    void 해결_후_재발은_새_건이다() {
        Incident first = report("OVERSELL:INC-NEW");
        incidentService.resolve(first.getId(), "실사 조정");

        Incident second = report("OVERSELL:INC-NEW");

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getOccurrenceCount()).isEqualTo(1);
        assertThat(incidentRepository.findAll())
                .filteredOn(i -> "OVERSELL:INC-NEW".equals(i.getDedupeKey()))
                .hasSize(2);
    }

    @Test
    @DisplayName("미해결 중복 행은 부분 유니크 인덱스가 차단한다")
    void 미해결_중복은_제약이_차단한다() {
        // report 를 우회해 강제로 두 번째 미해결 행을 넣는다 — "조회 후 없으면 삽입" 은
        // 경합 순간 뚫리므로, 최종 방어선인 인덱스가 실제로 동작하는지 확인해야 한다.
        incidentRepository.saveAndFlush(Incident.open(IncidentType.OVERSELL,
                "OVERSELL:INC-UNIQ", "제목", "상세", Instant.now()));

        assertThatThrownBy(() -> incidentRepository.saveAndFlush(Incident.open(
                IncidentType.OVERSELL, "OVERSELL:INC-UNIQ", "제목", "상세", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("해결된 행이 있으면 같은 키의 새 미해결 행을 넣을 수 있다")
    void 해결된_행은_제약에서_제외된다() {
        Incident resolved = incidentRepository.saveAndFlush(Incident.open(IncidentType.OVERSELL,
                "OVERSELL:INC-PARTIAL", "제목", "상세", Instant.now()));
        resolved.resolve(null);
        incidentRepository.saveAndFlush(resolved);

        Incident reopened = incidentRepository.saveAndFlush(Incident.open(IncidentType.OVERSELL,
                "OVERSELL:INC-PARTIAL", "제목", "상세", Instant.now()));

        assertThat(reopened.getId()).isNotEqualTo(resolved.getId());
    }

    // ------------------------------------------------------------------
    // 감시 경로 — 알림 억제와 무관하게 누적된다
    // ------------------------------------------------------------------

    @Test
    @DisplayName("정합성 검사를 반복하면 같은 인시던트에 횟수가 쌓인다")
    void 감시_경로_누적() {
        Sku sku = skuService.register("INC-MISMATCH", "불일치 상품", 100, 10);
        // 이력 없이 스냅샷만 남겨 인위적으로 어긋뜨린다 (MonitorIntegrationTest 와 같은 방식).
        stockMovementRepository.deleteAll(
                stockMovementRepository.findBySkuIdOrderByCreatedAtDesc(sku.getId()));

        stockConsistencyChecker.check();
        stockConsistencyChecker.check();

        assertThat(incidentRepository.findAll())
                .filteredOn(i -> "STOCK_MISMATCH:INC-MISMATCH".equals(i.getDedupeKey()))
                .singleElement()
                .satisfies(incident -> {
                    assertThat(incident.getType()).isEqualTo(IncidentType.STOCK_MISMATCH);
                    assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
                    // 두 번째 주기의 알림은 억제되지만 인시던트 횟수는 쌓인다.
                    assertThat(incident.getOccurrenceCount()).isEqualTo(2);
                });
    }

    // ------------------------------------------------------------------
    // 마감
    // ------------------------------------------------------------------

    @Test
    @DisplayName("확인·해결 전이가 영속된다")
    void 마감_전이_영속() {
        Incident incident = report("OVERSELL:INC-CLOSE");

        incidentService.acknowledge(incident.getId());
        incidentService.resolve(incident.getId(), "출고 보류 후 실재고 확인");

        Incident found = incidentService.get(incident.getId());
        assertThat(found.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(found.getAcknowledgedAt()).isNotNull();
        assertThat(found.getResolvedAt()).isNotNull();
        assertThat(found.getResolutionNote()).isEqualTo("출고 보류 후 실재고 확인");
    }

    @Test
    @DisplayName("미해결 건수는 해결을 제외하고 센다")
    void 미해결_건수() {
        long before = incidentService.countUnresolved();

        Incident open = report("OVERSELL:INC-COUNT-1");
        Incident acknowledged = report("OVERSELL:INC-COUNT-2");
        incidentService.acknowledge(acknowledged.getId());
        Incident resolved = report("OVERSELL:INC-COUNT-3");
        incidentService.resolve(resolved.getId(), null);

        assertThat(incidentService.countUnresolved()).isEqualTo(before + 2);
        assertThat(open.getStatus()).isEqualTo(IncidentStatus.OPEN);
    }
}
