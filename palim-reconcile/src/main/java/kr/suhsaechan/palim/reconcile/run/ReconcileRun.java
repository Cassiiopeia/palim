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
import kr.suhsaechan.palim.reconcile.define.Pairing;
import kr.suhsaechan.palim.reconcile.define.WarehouseScope;
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

    /** 양쪽 스냅샷이 공유하는 시각(칸). 이력이 한 줄로 이어지게 하는 값이다. */
    @Column(nullable = false)
    private Instant baseAt;

    /**
     * 왼쪽 원천에서 <b>실제로 합산한</b> 시각.
     *
     * <p>칸 시각과 다를 수 있다 — 합산은 각 원천이 실제로 가진 시각으로 하기 때문이다. 이 값이
     * 없으면 나중에 「이 차이가 어느 품목에서 나왔나」 를 되짚을 때 그 회차가 본 자료를 다시
     * 불러올 수 없고, 지금 담긴 최신 재고로 계산하게 되어 <b>합계가 어긋난다.</b>
     *
     * <p>옛 회차는 비어 있다.
     */
    private Instant leftBaseAt;

    /** 오른쪽 원천에서 실제로 합산한 시각. {@link #leftBaseAt} 참고. */
    private Instant rightBaseAt;

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

    /** 어느 시각의 자료를 봤는지 남긴다. 합산 직후 부른다. */
    /**
     * 이 회차가 <b>무엇을 견줬는지</b>.
     *
     * <p>정의를 보고 다시 계산하면 안 된다 — 설정을 바꾼 뒤 지난 회차를 열면 저장된 합계와
     * 화면의 상세가 어긋난다. 게다가 회차마다 맞기도 하고 틀리기도 해서 「늘 틀린다」 보다
     * 원인을 찾기 어렵다.
     *
     * <p>비어 있으면 「그때는 전 창고를 봤다」 로 읽는다 — 이 칸이 생기기 전 회차가 그랬다.
     */
    @Column(length = 1000)
    private String leftWarehouses;

    @Column(length = 1000)
    private String rightWarehouses;

    /** 이 회차가 더한 수치 칸. 비어 있으면 기본 칸. */
    @Column(length = 50)
    private String compareField;

    /** 이 회차가 실제로 본 범위를 남긴다. 실행 직후 한 번만 부른다. */
    public void recordScope(Pairing pairing) {
        this.leftWarehouses = pairing.leftScope().toStored();
        this.rightWarehouses = pairing.rightScope().toStored();
        this.compareField = pairing.compareField();
    }

    /**
     * 이 회차가 본 범위 그대로.
     *
     * <p>상세 화면이 «오늘의 정의» 가 아니라 이 값으로 다시 계산해야 저장된 합계와 맞는다.
     *
     * @param leftSource  회차에는 원천 이름을 남기지 않으므로 정의에서 받는다.
     *                    원천이 바뀌면 그것은 다른 대조다
     */
    public Pairing scopeOf(String leftSource, String rightSource) {
        return new Pairing(leftSource, rightSource,
                WarehouseScope.parse(leftWarehouses), WarehouseScope.parse(rightWarehouses),
                compareField);
    }

    public void recordSourceTimes(Instant leftBaseAt, Instant rightBaseAt) {
        this.leftBaseAt = leftBaseAt;
        this.rightBaseAt = rightBaseAt;
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
