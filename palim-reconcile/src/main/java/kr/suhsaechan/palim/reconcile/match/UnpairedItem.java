package kr.suhsaechan.palim.reconcile.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 「이 품목은 짝이 없다」 고 사람이 정해 둔 것.
 *
 * <p>왜 필요한가. 이것이 없으면 <b>할 일 개수가 영영 0이 되지 않는다.</b> 한쪽에만 있는 품목은
 * 언제나 남는다 — 단종됐거나, 이쪽 시스템만 쓰는 부자재이거나, 이번 대조 범위 밖이다. 그것들이
 * 계속 「묶을 짝이 없는 것 9건」 으로 떠 있으면 사람은 <b>다 했는지 아닌지를 알 수 없고</b>, 결국
 * 그 숫자를 안 보게 된다. 안 보는 숫자는 없는 것과 같다.
 *
 * <p><b>지우지 않고 표시만 한다.</b> 단종인 줄 알았는데 다시 들어오는 일이 실제로 일어나므로
 * 되돌릴 수 있어야 한다. 또 원본 재고 자료를 건드리지 않으므로 재고를 다시 담아도 이 표시는
 * 그대로 남는다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "reconcile_item_unpaired")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnpairedItem extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 50)
    private String source;

    @Column(nullable = false, length = 255)
    private String itemRef;

    /** 왜 짝이 없는지. 「단종만 빼고 다시 보기」 같은 것을 하려면 이유가 남아야 한다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Reason reason;

    @Column(nullable = false, length = 500)
    private String note;

    private UnpairedItem(UUID tenantId, String source, String itemRef, Reason reason, String note) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.source = source;
        this.itemRef = itemRef;
        this.reason = reason == null ? Reason.NO_COUNTERPART : reason;
        this.note = note == null ? "" : note;
    }

    public static UnpairedItem of(UUID tenantId, String source, String itemRef, Reason reason,
                                  String note) {
        return new UnpairedItem(tenantId, source, itemRef, reason, note);
    }

    public void update(Reason reason, String note) {
        this.reason = reason == null ? Reason.NO_COUNTERPART : reason;
        this.note = note == null ? "" : note;
    }

    /** 왜 짝이 없는가. 사람이 고르는 값이라 화면에 그대로 쓸 말로 둔다. */
    public enum Reason {

        /** 상대 시스템에 애초에 없는 묶음. 부자재·사은품처럼 한쪽만 관리하는 것들. */
        NO_COUNTERPART("상대 쪽에 없는 묶음"),

        /** 더 안 쓰는 품목. 재고가 0이 될 때까지 목록에 남는다. */
        DISCONTINUED("단종·더 안 씀"),

        /** 이번 대조에서 볼 대상이 아닌 것. */
        OUT_OF_SCOPE("이번 대조 대상 아님");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }
}
