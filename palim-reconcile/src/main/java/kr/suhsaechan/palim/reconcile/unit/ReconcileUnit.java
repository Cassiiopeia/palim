package kr.suhsaechan.palim.reconcile.unit;

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
 * 정합 단위 — 대조의 기본 단위.
 *
 * <p>같은 물건이 원천마다 <b>다른 개수로 잡힌다.</b> 전산은 「1박스」로, 물류는 「낱개 12개」로
 * 센다. 그 둘을 같은 것으로 보려면 «무엇을 하나로 볼지» 를 사람이 정해야 한다.
 *
 * <p>품목 코드로 맞추지 않는 이유가 여기 있다 — 두 시스템의 코드 체계가 서로 무관해서, 코드를
 * 기준으로 삼으면 애초에 맞출 수가 없다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "reconcile_unit")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconcileUnit extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** 사람이 정하는 식별자. 화면과 결과에 그대로 쓴다. */
    @Column(nullable = false, length = 100)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    /** 이 단위를 세는 기준. 원천 단위가 무엇이든 여기로 환산해 비교한다. */
    @Column(nullable = false, length = 20)
    private String baseUnit;

    @Column(nullable = false)
    private boolean isActive;

    private ReconcileUnit(UUID tenantId, String code, String name, String baseUnit) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.baseUnit = baseUnit;
        this.isActive = true;
    }

    public static ReconcileUnit of(UUID tenantId, String code, String name, String baseUnit) {
        return new ReconcileUnit(tenantId, code, name, baseUnit);
    }

    public void rename(String name) {
        this.name = name;
    }

    /**
     * 더 이상 대조하지 않는다.
     *
     * <p>지우지 않는 이유는 과거 실행 기록이 이 단위를 가리키기 때문이다. 지우면 «작년에 왜
     * 이런 결과가 나왔나» 에 답할 수 없다.
     */
    public void deactivate() {
        this.isActive = false;
    }
}
