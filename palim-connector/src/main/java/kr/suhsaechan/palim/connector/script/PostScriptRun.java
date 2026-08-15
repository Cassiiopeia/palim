package kr.suhsaechan.palim.connector.script;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.tenant.TenantFilters;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

/**
 * 스크립트가 언제 어느 버전으로 돌았고 몇 건이 바뀌었나.
 *
 * <p><b>「몇 건 바뀌었나」가 이 기록의 핵심이다.</b> 스크립트가 조용히 아무것도 안 하게 되면
 * — 상대가 이름 표기를 바꿨다거나, 매핑을 고쳐 칸 이름이 달라졌다거나 — 이름만 안 다듬어진
 * 채로 대조가 계속 돈다. 「어제는 69건이 바뀌었는데 오늘 0건」 을 알아채려면 숫자가 남아야 한다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "connector_post_script_run")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostScriptRun {

    /** 화면과 로그가 이것으로 가득 차지 않게. 원인은 대개 앞부분에 있다. */
    private static final int SUMMARY_LIMIT = 1000;

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID scriptId;

    @Column(nullable = false)
    private int scriptVersion;

    /** 어느 적재에 딸려 돌았나. 시험 삼아 따로 돌리면 비어 있다. */
    private UUID connectorRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostScriptResult.Status status;

    @Column(nullable = false)
    private int totalCount;

    @Column(nullable = false)
    private int changedCount;

    /** 스크립트가 남긴 말. 사람이 print 로 디버깅할 수 있어야 한다. */
    @Column(columnDefinition = "text")
    private String stderrTail;

    @Column(length = 1000)
    private String errorSummary;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    private PostScriptRun(UUID tenantId, PostScript script, UUID connectorRunId,
                          PostScriptResult.Status status, int total, int changed,
                          String stderrTail, String errorSummary, long elapsedMs) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.scriptId = script.getId();
        this.scriptVersion = script.getVersion();
        this.connectorRunId = connectorRunId;
        this.status = status;
        this.totalCount = total;
        this.changedCount = changed;
        this.stderrTail = stderrTail;
        this.errorSummary = cut(errorSummary);
        this.finishedAt = Instant.now();
        this.startedAt = this.finishedAt.minusMillis(Math.max(elapsedMs, 0));
    }

    public static PostScriptRun succeeded(UUID tenantId, PostScript script, UUID connectorRunId,
                                          int total, int changed, PostScriptResult result) {
        return new PostScriptRun(tenantId, script, connectorRunId, result.status(),
                total, changed, result.message(), null, result.elapsedMs());
    }

    public static PostScriptRun failed(UUID tenantId, PostScript script, UUID connectorRunId,
                                       int total, PostScriptResult result) {
        return new PostScriptRun(tenantId, script, connectorRunId, result.status(),
                total, 0, result.message(), result.message(), result.elapsedMs());
    }

    private static String cut(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= SUMMARY_LIMIT ? text : text.substring(0, SUMMARY_LIMIT);
    }
}
