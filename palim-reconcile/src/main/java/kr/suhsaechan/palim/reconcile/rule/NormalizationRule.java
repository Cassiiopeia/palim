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
 * <p>같은 물건인데 원천마다 이름이 다르게 적힌다. 「제품A 16g (26.11.07)」과 「제품A16g」이
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

    private NormalizationRule(UUID tenantId, String name, String pattern, String replacement,
                              int sortOrder) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.name = name;
        this.pattern = pattern;
        this.replacement = replacement == null ? "" : replacement;
        this.sortOrder = sortOrder;
        this.isActive = true;
    }

    public static NormalizationRule of(UUID tenantId, String name, String pattern,
                                       String replacement, int sortOrder) {
        return new NormalizationRule(tenantId, name, pattern, replacement, sortOrder);
    }

    public void update(String name, String pattern, String replacement, int sortOrder) {
        this.name = name;
        this.pattern = pattern;
        this.replacement = replacement == null ? "" : replacement;
        this.sortOrder = sortOrder;
    }

    public void deactivate() {
        this.isActive = false;
    }

    /** 다시 켠다. 껐다 켜 보며 매칭 개수가 어떻게 변하는지 확인하는 것이 흔한 작업이다. */
    public void activate() {
        this.isActive = true;
    }
}
