package kr.suhsaechan.palim.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 도메인 서비스.
 *
 * <h2>기록 실패가 본 작업을 되돌려서는 안 된다</h2>
 *
 * <p>{@link #record(AuditRecord)} 는 {@code REQUIRES_NEW} 로 별도 트랜잭션을 열고 예외를
 * 삼킨다. 재고 조정은 성공했는데 감사 기록 INSERT 가 실패해 <b>재고 조정까지 롤백되면 그것이
 * 더 큰 사고</b>다. 반대로 기록만 유실되면 애플리케이션 로그에 흔적이 남는다.
 *
 * <p>이 판단은 이 시스템이 규제 감사 대상이 아니기 때문이다. "기록 없이는 작업도 없다"가
 * 요구되는 환경이라면 예외를 그대로 전파해야 한다.
 *
 * <h2>다른 도메인을 의존하지 않는다</h2>
 *
 * <p>actor·대상을 전부 문자열 값으로 받는다. 감사 로그는 모든 도메인에서 호출되므로 특정
 * 도메인 타입을 참조하면 순환 의존이 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /**
     * 감사 로그를 남긴다.
     *
     * <p>호출부의 트랜잭션과 분리된다. 호출부가 나중에 롤백돼도 이 기록은 남는다 — 시도 자체가
     * 감사 대상이므로 의도된 동작이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditRecord record) {
        try {
            auditLogRepository.save(AuditLog.of(record));
        } catch (RuntimeException exception) {
            // 여기서 예외를 올리면 감사 대상 작업이 롤백된다. 로그로만 남긴다.
            log.error("감사 로그 기록 실패 — {} actor={} target={}/{}",
                    record.auditType(), record.actorId(),
                    record.targetType(), record.targetId(), exception);
        }
    }

    /**
     * 감사 로그를 검색한다.
     *
     * <p>정렬은 항상 발생 시각 내림차순으로 고정한다. 호출부가 정렬을 정하게 하면 인덱스를
     * 타지 않는 정렬이 들어와 목록 조회가 느려진다.
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> search(AuditSearchCondition condition, Pageable pageable) {
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "occurredAt"));

        return auditLogRepository.findAll(AuditLogSpecifications.of(condition), sorted);
    }

    /**
     * 보존기간이 지난 기록을 지운다.
     *
     * <p>감사 로그는 화면 조회까지 기록하므로 가장 빠르게 증가한다. 정리하지 않으면 디스크가
     * 먼저 찬다.
     *
     * @return 삭제된 행 수
     */
    @Transactional
    public int purgeOlderThan(int retentionDays) {
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("보존 일수는 1 이상이어야 한다: " + retentionDays);
        }

        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        long targetCount = auditLogRepository.countByOccurredAtBefore(cutoff);
        if (targetCount == 0) {
            return 0;
        }

        int deleted = auditLogRepository.deleteByOccurredAtBefore(cutoff);
        log.info("감사 로그 정리 — {} 이전 {}건 삭제 (보존 {}일)", cutoff, deleted, retentionDays);
        return deleted;
    }
}
