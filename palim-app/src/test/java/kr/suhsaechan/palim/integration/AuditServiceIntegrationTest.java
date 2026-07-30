package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import kr.suhsaechan.palim.audit.AuditLog;
import kr.suhsaechan.palim.audit.AuditRecord;
import kr.suhsaechan.palim.audit.AuditSearchCondition;
import kr.suhsaechan.palim.audit.AuditSearchField;
import kr.suhsaechan.palim.audit.AuditService;
import kr.suhsaechan.palim.audit.AuditType;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 감사 로그 기록·검색·정리 통합 검증.
 *
 * <p>{@code record()} 가 {@code REQUIRES_NEW} 라 호출자 트랜잭션과 분리된다 — 클래스에
 * {@code @Transactional} 을 붙이면 커밋된 기록이 테스트 롤백으로 지워지지 않으므로 붙이지
 * 않고, 테스트가 직접 정리한다.
 */
class AuditServiceIntegrationTest extends IntegrationTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private kr.suhsaechan.palim.audit.AuditLogRepository auditLogRepository;

    private AuditSearchCondition allOf(Set<AuditType> types, AuditSearchField field, String keyword) {
        Instant now = Instant.now();
        return new AuditSearchCondition(now.minus(1, ChronoUnit.HOURS),
                now.plus(1, ChronoUnit.MINUTES), types, field, keyword);
    }

    @Test
    @DisplayName("기록하고 유형·검색어로 다시 찾는다")
    void 기록_후_검색() {
        try {
            auditService.record(AuditRecord.of(AuditType.LOGIN_SUCCESS)
                    .actor("audit-it-admin", null)
                    .clientIp("10.203.255.1")
                    .build());
            auditService.record(AuditRecord.of(AuditType.STOCK_ADJUST)
                    .actor("audit-it-admin", null)
                    .target("SKU", "SKU-777")
                    .summary("SKU SKU-777 실사 조정 — 10 → 7")
                    .build());

            Page<AuditLog> byType = auditService.search(
                    allOf(Set.of(AuditType.LOGIN_SUCCESS), AuditSearchField.ACTOR_ID, "audit-it-admin"),
                    PageRequest.of(0, 10));
            assertThat(byType.getContent())
                    .allMatch(log -> log.getAuditType() == AuditType.LOGIN_SUCCESS);
            assertThat(byType.getTotalElements()).isEqualTo(1);

            Page<AuditLog> byKeyword = auditService.search(
                    allOf(Set.of(), AuditSearchField.SUMMARY, "SKU-777"),
                    PageRequest.of(0, 10));
            assertThat(byKeyword.getTotalElements()).isEqualTo(1);
            assertThat(byKeyword.getContent().getFirst().getTargetId()).isEqualTo("SKU-777");
        } finally {
            auditLogRepository.deleteAll(
                    auditLogRepository.findAll().stream()
                            .filter(log -> "audit-it-admin".equals(log.getActorId()))
                            .toList());
        }
    }

    @Test
    @DisplayName("검색어의 LIKE 와일드카드는 리터럴로 다룬다")
    void 와일드카드_이스케이프() {
        try {
            auditService.record(AuditRecord.of(AuditType.VIEW)
                    .actor("audit-it-wild", null)
                    .summary("재고 관리 을(를) 조회했습니다.")
                    .build());

            // "%" 하나로 전체가 걸리면 안 된다.
            Page<AuditLog> result = auditService.search(
                    allOf(Set.of(), AuditSearchField.SUMMARY, "%"), PageRequest.of(0, 10));

            assertThat(result.getTotalElements()).isZero();
        } finally {
            auditLogRepository.deleteAll(
                    auditLogRepository.findAll().stream()
                            .filter(log -> "audit-it-wild".equals(log.getActorId()))
                            .toList());
        }
    }

    @Test
    @DisplayName("보존기간이 지난 기록만 지운다")
    void 보존기간_정리() {
        try {
            auditService.record(AuditRecord.of(AuditType.LOGIN_SUCCESS)
                    .occurredAt(Instant.now().minus(400, ChronoUnit.DAYS))
                    .actor("audit-it-purge", null)
                    .build());
            auditService.record(AuditRecord.of(AuditType.LOGIN_SUCCESS)
                    .actor("audit-it-purge", null)
                    .build());

            int deleted = auditService.purgeOlderThan(365);

            assertThat(deleted).isGreaterThanOrEqualTo(1);
            assertThat(auditLogRepository.findAll().stream()
                    .filter(log -> "audit-it-purge".equals(log.getActorId())))
                    .hasSize(1);
        } finally {
            auditLogRepository.deleteAll(
                    auditLogRepository.findAll().stream()
                            .filter(log -> "audit-it-purge".equals(log.getActorId()))
                            .toList());
        }
    }
}
