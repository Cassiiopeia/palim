package kr.suhsaechan.palim.audit;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * 감사 로그 검색 조건을 JPA Specification 으로 변환한다.
 *
 * <p>기간 · 유형 다중선택 · 대상 필드별 검색어는 파생 쿼리 메서드로 표현할 수 없고, 조합마다
 * 메서드를 만들면 8개가 필요하다.
 */
final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    static Specification<AuditLog> of(AuditSearchCondition condition) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), condition.from()));
            predicates.add(builder.lessThan(root.get("occurredAt"), condition.to()));

            if (condition.hasTypeFilter()) {
                predicates.add(root.get("auditType").in(condition.types()));
            }

            if (condition.hasKeyword()) {
                predicates.add(likeIgnoreCase(root, builder, condition));
            }

            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * 부분 일치 검색.
     *
     * <p>사용자 입력에 {@code %} 나 {@code _} 가 들어오면 와일드카드로 해석돼 의도하지 않은
     * 전체 검색이 된다. 이스케이프해서 리터럴로 다룬다.
     */
    private static Predicate likeIgnoreCase(jakarta.persistence.criteria.Root<AuditLog> root,
                                            jakarta.persistence.criteria.CriteriaBuilder builder,
                                            AuditSearchCondition condition) {
        String column = switch (condition.field()) {
            case ACTOR_ID -> "actorId";
            case ACTOR_NAME -> "actorName";
            case CLIENT_IP -> "clientIp";
            case SUMMARY -> "summary";
        };

        String pattern = "%" + escapeLike(condition.keyword().toLowerCase()) + "%";
        return builder.like(builder.lower(root.get(column)), pattern, '\\');
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
