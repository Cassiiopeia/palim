package kr.suhsaechan.palim.reconcile.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.tenant.TenantFilters;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

/**
 * 차이 한 줄.
 *
 * <p>{@code firstSeenRunId} 가 승격 판정의 근거다. 이 차이가 처음 관찰된 실행을 기억해 두면
 * «며칠째 이러고 있나» 를 셀 수 있다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "reconcile_diff")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconcileDiff extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID runId;

    /** 비어 있으면 미매칭 — 아직 어느 단위에도 속하지 않은 품목이다. */
    private UUID unitId;

    @Column(nullable = false, length = 100)
    private String unitCode;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal leftQuantity;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal rightQuantity;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal delta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiffType diffType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiffState state;

    @Column(nullable = false, length = 20)
    private String actionStatus;

    @Column(length = 1000)
    private String actionNote;

    /** 이 차이가 처음 관찰된 실행. 승격 판정의 근거다. */
    private UUID firstSeenRunId;

    private ReconcileDiff(UUID tenantId, UUID runId, UUID unitId, String unitCode,
                          BigDecimal leftQuantity, BigDecimal rightQuantity, BigDecimal delta,
                          DiffType diffType, DiffState state, UUID firstSeenRunId) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.runId = runId;
        this.unitId = unitId;
        this.unitCode = unitCode == null ? "" : unitCode;
        this.leftQuantity = leftQuantity;
        this.rightQuantity = rightQuantity;
        this.delta = delta;
        this.diffType = diffType;
        this.state = state;
        this.actionStatus = "UNCHECKED";
        this.firstSeenRunId = firstSeenRunId == null ? runId : firstSeenRunId;
    }

    public static ReconcileDiff of(UUID tenantId, UUID runId, UUID unitId, String unitCode,
                                   BigDecimal leftQuantity, BigDecimal rightQuantity,
                                   BigDecimal delta, DiffType diffType, DiffState state,
                                   UUID firstSeenRunId) {
        return new ReconcileDiff(tenantId, runId, unitId, unitCode, leftQuantity, rightQuantity,
                delta, diffType, state, firstSeenRunId);
    }

    /** 사람이 처리했다. */
    public void resolve(String note) {
        this.state = DiffState.RESOLVED;
        this.actionStatus = "DONE";
        this.actionNote = note;
    }

    /** 알면서 두기로 했다. 다음 회차에 다시 올라와도 조용히 둔다. */
    public void ignore(String note) {
        this.state = DiffState.IGNORED;
        this.actionStatus = "IGNORED";
        this.actionNote = note;
    }

    public void markChecking(String note) {
        this.actionStatus = "CHECKING";
        this.actionNote = note;
    }

    public boolean isConfirmed() {
        return state == DiffState.CONFIRMED;
    }
}
