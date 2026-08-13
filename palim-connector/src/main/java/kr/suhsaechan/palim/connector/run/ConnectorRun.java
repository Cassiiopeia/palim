package kr.suhsaechan.palim.connector.run;

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
import kr.suhsaechan.palim.connector.support.ColumnText;
import kr.suhsaechan.palim.common.tenant.TenantFilters;
import org.hibernate.annotations.Filter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 실행 1건.
 *
 * <p>{@code mappingVersion} 을 값으로 박아 둔다. 정의를 나중에 바꿔도 "지난달 데이터가 왜
 * 이런가"에 답할 수 있어야 하기 때문이다. 매핑을 참조만 하면 정의가 바뀌는 순간 과거를
 * 설명할 방법이 사라진다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "connector_run")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorRun extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID connectorId;

    @Column(nullable = false)
    private UUID mappingId;

    @Column(nullable = false)
    private int mappingVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RunMode runMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private RunTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status;

    @Column(nullable = false)
    private int totalCount;

    @Column(nullable = false)
    private int successCount;

    @Column(nullable = false)
    private int failedCount;

    @Column(length = 1000)
    private String errorSummary;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    private ConnectorRun(UUID tenantId, UUID connectorId, UUID mappingId, int mappingVersion,
                         RunMode runMode, RunTrigger triggerType) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.connectorId = connectorId;
        this.mappingId = mappingId;
        this.mappingVersion = mappingVersion;
        this.runMode = runMode;
        this.triggerType = triggerType;
        this.status = RunStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public static ConnectorRun start(UUID tenantId, UUID connectorId, UUID mappingId,
                                     int mappingVersion, RunMode runMode, RunTrigger triggerType) {
        return new ConnectorRun(tenantId, connectorId, mappingId, mappingVersion,
                runMode, triggerType);
    }

    /**
     * 실행 종료.
     *
     * <p>실패 행이 하나라도 있으면 {@code PARTIAL} 이다. 성공으로 표시하면 사람이 실패 행을
     * 보지 않게 되고, 실패로 표시하면 성공분까지 버린 것으로 오해한다.
     */
    public void finish(int total, int success, int failed) {
        this.totalCount = total;
        this.successCount = success;
        this.failedCount = failed;
        this.status = failed == 0 ? RunStatus.SUCCEEDED : RunStatus.PARTIAL;
        this.finishedAt = Instant.now();
    }

    /** {@code error_summary} 컬럼 길이. 초과하면 PostgreSQL 이 22001 로 중단시킨다. */
    private static final int ERROR_SUMMARY_MAX_LENGTH = 1000;

    /**
     * 실행 실패.
     *
     * <p>요약을 잘라서 넣는다. 실패를 기록하려다 그 기록이 또 실패하면 무엇이 잘못됐는지
     * 알 방법이 사라진다.
     */
    public void fail(String summary) {
        this.status = RunStatus.FAILED;
        this.errorSummary = ColumnText.truncate(summary, ERROR_SUMMARY_MAX_LENGTH);
        this.finishedAt = Instant.now();
    }

    public void markRolledBack() {
        this.status = RunStatus.ROLLED_BACK;
    }

    public boolean isTest() {
        return runMode == RunMode.TEST;
    }
}
