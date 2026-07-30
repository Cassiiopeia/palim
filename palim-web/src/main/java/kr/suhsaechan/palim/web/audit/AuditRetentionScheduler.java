package kr.suhsaechan.palim.web.audit;

import kr.suhsaechan.palim.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보존기간이 지난 감사 로그를 정리한다.
 *
 * <p>감사 로그는 화면 조회까지 기록하므로 가장 빠르게 쌓이는 테이블이다. 정리 배치가 없으면
 * 보존기간 설정은 장식이고 디스크가 먼저 찬다.
 *
 * <p>새벽 시간대 고정 실행. 벌크 DELETE 는 지워지는 행 수만큼 잠금을 잡으므로 발주자가 쓰지
 * 않는 시간에 돌린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRetentionScheduler {

    private final AuditService auditService;
    private final AuditRetentionProperties auditRetentionProperties;

    /** KST 04:10. 다른 새벽 배치(일일 리포트 등)와 겹치지 않게 10분을 비껴둔다. */
    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
    public void purge() {
        int deleted = auditService.purgeOlderThan(auditRetentionProperties.retentionDays());
        if (deleted > 0) {
            log.info("감사 로그 정리 완료 — {}건 삭제", deleted);
        }
    }

    /**
     * 감사 로그 보존 설정.
     *
     * @param retentionDays 보존 일수. 기본 365
     */
    @ConfigurationProperties(prefix = "palim.audit")
    public record AuditRetentionProperties(int retentionDays) {

        private static final int DEFAULT_RETENTION_DAYS = 365;

        public AuditRetentionProperties {
            retentionDays = retentionDays > 0 ? retentionDays : DEFAULT_RETENTION_DAYS;
        }
    }
}
