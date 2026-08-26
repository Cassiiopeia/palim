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
import kr.suhsaechan.palim.reconcile.filter.FilterSnapshot;
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

    /**
     * 무엇이 이 회차를 시작했나.
     *
     * <p>정해진 시각에 도는 쪽이 「오늘 이미 돌았나」 를 이력으로 판단하므로, 사람이 누른
     * 회차와 섞이면 <b>한 번 눌렀다는 이유로 그날 자동 대조를 건너뛴다.</b>
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconcileTrigger triggerType;

    private ReconcileRun(UUID tenantId, UUID definitionId, Instant baseAt,
                         ReconcileTrigger triggerType) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.definitionId = definitionId;
        this.baseAt = baseAt;
        this.triggerType = triggerType;
        this.status = RunStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public static ReconcileRun start(UUID tenantId, UUID definitionId, Instant baseAt,
                                     ReconcileTrigger triggerType) {
        return new ReconcileRun(tenantId, definitionId, baseAt, triggerType);
    }

    /** 어느 시각의 자료를 봤는지 남긴다. 합산 직후 부른다. */
    /**
     * 이 회차가 <b>무엇을 견줬는지</b>.
     *
     * <p>정의를 보고 다시 계산하면 안 된다 — 설정을 바꾼 뒤 지난 회차를 열면 저장된 합계와
     * 화면의 상세가 어긋난다. 게다가 회차마다 맞기도 하고 틀리기도 해서 「늘 틀린다」 보다
     * 원인을 찾기 어렵다.
     *
     * <p><b>V35 부터는 {@code filtersJson} 이 이 자리를 대신한다.</b> 이 두 칸은 그 이전
     * 회차를 읽기 위해서만 남아 있다 — 지우면 그 회차가 무엇을 봤는지 알 방법이 없어진다.
     */
    @Column(length = 1000)
    private String leftWarehouses;

    @Column(length = 1000)
    private String rightWarehouses;

    /** 이 회차가 더한 수치 칸. 비어 있으면 기본 칸. */
    @Column(length = 50)
    private String compareField;

    /**
     * 이 회차가 쓴 조건. 좌·우를 <b>사람이 읽는 식</b>으로 적는다.
     *
     * <p>구조화해 담지 않는 이유가 있다 — 그 글이 곧 다시 계산할 수 있는 기록이고, 카탈로그에서
     * 사라진 칸도 그대로 남으며, 읽는 쪽이 JSON 매핑에 매이지 않는다.
     */
    @Column(length = 2000)
    private String filtersLeft;

    @Column(length = 2000)
    private String filtersRight;

    /**
     * 이 회차가 실제로 본 조건을 남긴다. 실행 직후 한 번만 부른다.
     *
     * <p>상대 날짜는 <b>풀어서 담지 않는다.</b> 회차는 자기가 돈 시각을 이미 알므로 필요할 때
     * 다시 풀면 똑같은 답이 나온다 — 파생값을 저장하면 저장한 것과 다시 푼 것이 어긋날 자리만
     * 생긴다.
     */
    public void recordScope(Pairing pairing) {
        this.filtersLeft = pairing.leftFilter().describe();
        this.filtersRight = pairing.rightFilter().describe();
        this.compareField = pairing.compareField();
    }

    /**
     * 이 회차가 쓴 조건.
     *
     * <p>V35 이전 회차는 이 칸이 비어 있다 — 그때의 기록인 옛 창고 칸을 읽는다. 기록이 사라지면
     * 「그 회차는 무엇을 봤나」 에 답할 방법이 없어진다.
     */
    public FilterSnapshot getFilters() {
        boolean recorded = filtersLeft != null || filtersRight != null;
        return new FilterSnapshot(
                recorded ? filtersLeft : FilterSnapshot.fromLegacyCsv(leftWarehouses),
                recorded ? filtersRight : FilterSnapshot.fromLegacyCsv(rightWarehouses),
                compareField,
                startedAt);
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
