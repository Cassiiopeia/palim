package kr.suhsaechan.palim.reconcile.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.tenant.TenantFilters;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

/**
 * 대조 한 번의 기록.
 *
 * <p>실패도 «남긴다». 기준 시각이 어긋나 거부한 것도 기록이어야 사람이 «어제는 왜 안 돌았나» 에
 * 답할 수 있다. 조용히 넘어가면 며칠째 대조가 안 되고 있다는 사실을 아무도 모른다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "reconcile_run")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconcileRun extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID definitionId;

    /** 양쪽 스냅샷이 공유하는 시각. */
    @Column(nullable = false)
    private Instant baseAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status;

    @Column(nullable = false)
    private int leftCount;

    @Column(nullable = false)
    private int rightCount;

    @Column(nullable = false)
    private int diffCount;

    @Column(nullable = false)
    private int unmatchedCount;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    @Column(length = 1000)
    private String message;

    private ReconcileRun(UUID tenantId, UUID definitionId, Instant baseAt) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.definitionId = definitionId;
        this.baseAt = baseAt;
        this.status = RunStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public static ReconcileRun start(UUID tenantId, UUID definitionId, Instant baseAt) {
        return new ReconcileRun(tenantId, definitionId, baseAt);
    }

    public void succeed(int leftCount, int rightCount, int diffCount, int unmatchedCount) {
        this.status = RunStatus.SUCCESS;
        this.leftCount = leftCount;
        this.rightCount = rightCount;
        this.diffCount = diffCount;
        this.unmatchedCount = unmatchedCount;
        this.finishedAt = Instant.now();
    }

    /** 사유를 남긴다. «왜 안 돌았나» 에 답할 수 있어야 사람이 고칠 수 있다. */
    public void fail(String message) {
        this.status = RunStatus.FAILED;
        this.message = message;
        this.finishedAt = Instant.now();
    }

    public boolean isSuccess() {
        return status == RunStatus.SUCCESS;
    }
}
