package kr.suhsaechan.palim.audit;

import java.time.Instant;
import java.util.Set;

/**
 * 감사 로그 검색 조건.
 *
 * <h2>기간은 항상 필수다</h2>
 *
 * <p>{@code from} / {@code to} 를 선택 조건으로 두면 화면에서 전체 조회가 가능해지고, 감사 로그는
 * 가장 빠르게 쌓이는 테이블이라 <b>전체 스캔 한 번이 운영을 멈춘다.</b> 호출부가 기간을 반드시
 * 정하게 한다.
 *
 * @param types 비어 있으면 전체 유형
 * @param keyword 비어 있으면 {@code field} 를 무시한다
 */
public record AuditSearchCondition(
        Instant from,
        Instant to,
        Set<AuditType> types,
        AuditSearchField field,
        String keyword
) {

    public AuditSearchCondition {
        if (from == null || to == null) {
            throw new IllegalArgumentException("조회 기간은 필수다");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("종료 시각이 시작 시각보다 앞선다");
        }
        types = types == null ? Set.of() : Set.copyOf(types);
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    public boolean hasKeyword() {
        return keyword != null && field != null;
    }

    public boolean hasTypeFilter() {
        return !types.isEmpty();
    }
}
