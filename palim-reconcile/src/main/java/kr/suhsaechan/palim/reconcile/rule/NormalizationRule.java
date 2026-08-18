package kr.suhsaechan.palim.reconcile.rule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.tenant.TenantFilters;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

/**
 * 품명 정규화 규칙 하나.
 *
 * <p>같은 묶음인데 원천마다 이름이 다르게 적힌다. 「제품A 16g (26.11.07)」과 「제품A16g」이
 * 그렇다. 괄호 안 유통기한을 떼고 공백을 지우면 같은 이름이 된다.
 *
 * <p><b>이 규칙은 후보를 좁힐 뿐 확정하지 않는다.</b> 규칙이 틀리면 엉뚱한 품목을 합쳐 놓고
 * "재고가 맞는다"고 보고하는데, 이건 불일치를 못 찾는 것보다 나쁘다 — 틀렸다는 사실조차
 * 드러나지 않는다.
 *
 * <p>원본 품명은 {@code std_stock_snapshot.raw_item_name} 에 그대로 있으므로 규칙을 고친 뒤
 * 다시 계산할 수 있다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "normalization_rule")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NormalizationRule extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 500)
    private String pattern;

    @Column(nullable = false, length = 200)
    private String replacement;

    /** 작은 값부터 적용한다. 순서가 바뀌면 결과가 달라진다. */
    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean isActive;

    /**
     * 이 규칙을 걸 원천. 비어 있으면 모든 원천.
     *
     * <p>원천마다 표기 습관이 다르다. 한쪽만 밑줄 뒤에 유통기한을 붙인다면 그 규칙은 그쪽에만
     * 걸어야 한다 — 양쪽에 걸면 지금은 무해해도 같은 기호를 다른 뜻으로 쓰는 원천이 붙는
     * 순간 조용히 망가진다.
     */
    @Column(length = 100)
    private String sourceCode;

    private NormalizationRule(UUID tenantId, String name, String pattern, String replacement,
                              int sortOrder, String sourceCode) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.name = name;
        this.pattern = pattern;
        this.replacement = replacement == null ? "" : replacement;
        this.sortOrder = sortOrder;
        this.isActive = true;
        this.sourceCode = blankToNull(sourceCode);
    }

    public static NormalizationRule of(UUID tenantId, String name, String pattern,
                                       String replacement, int sortOrder) {
        return new NormalizationRule(tenantId, name, pattern, replacement, sortOrder, null);
    }

    public static NormalizationRule of(UUID tenantId, String name, String pattern,
                                       String replacement, int sortOrder, String sourceCode) {
        return new NormalizationRule(tenantId, name, pattern, replacement, sortOrder, sourceCode);
    }

    public void update(String name, String pattern, String replacement, int sortOrder) {
        this.name = name;
        this.pattern = pattern;
        this.replacement = replacement == null ? "" : replacement;
        this.sortOrder = sortOrder;
    }

    public void update(String name, String pattern, String replacement, int sortOrder,
                       String sourceCode) {
        update(name, pattern, replacement, sortOrder);
        this.sourceCode = blankToNull(sourceCode);
    }

    /** 순서만 바꾼다. 끌어서 옮길 때는 다른 값이 함께 실려 오지 않는다. */
    public void moveTo(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    /**
     * 이 원천에 거는 규칙인가.
     *
     * @param source 스냅샷의 {@code source}. {@code null} 이면 원천을 가리지 않는 자리라
     *               모든 규칙이 걸린다 — 미리보기처럼 원천이 정해지지 않은 화면이 그렇다
     */
    public boolean appliesTo(String source) {
        return sourceCode == null || source == null || sourceCode.equals(source);
    }

    /** 화면의 빈 칸은 「모든 원천」이다. 빈 문자열로 저장하면 어느 원천에도 안 걸린다. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public void deactivate() {
        this.isActive = false;
    }

    /** 다시 켠다. 껐다 켜 보며 매칭 개수가 어떻게 변하는지 확인하는 것이 흔한 작업이다. */
    public void activate() {
        this.isActive = true;
    }
}
