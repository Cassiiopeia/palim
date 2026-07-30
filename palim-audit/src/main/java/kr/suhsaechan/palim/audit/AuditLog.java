package kr.suhsaechan.palim.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 감사 로그 1건.
 *
 * <h2>불변 기록이다</h2>
 *
 * <p>수정 메서드를 두지 않는다. 감사 로그는 <b>고칠 수 있으면 감사 기능이 아니다.</b> 잘못
 * 기록된 값도 그대로 남기고, 정정이 필요하면 새 행을 추가한다.
 *
 * <p>{@code BaseTimeEntity} 를 상속하지 않는다. {@code updatedAt} 이 존재하면 "수정될 수 있는
 * 기록"이라는 잘못된 신호를 준다. 시각은 {@link #occurredAt} 하나만 둔다.
 *
 * <h2>actor 를 FK 로 두지 않는다</h2>
 *
 * <p>{@link #actorId} 는 {@code admin_account} 를 참조하는 외래키가 아니다. 계정을 지우거나
 * 아이디를 바꿔도 <b>그 시점에 누가 했는지</b>가 남아야 하므로 값을 복사해 보관한다. 존재하지
 * 않는 계정으로 로그인을 시도한 경우도 기록해야 하는데, FK 로는 아예 저장할 수 없다.
 */
@Getter
@Entity
@Table(name = "audit_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** 관리자 아이디. 인증 전 실패 기록에는 입력된 아이디가 들어간다. */
    @Column(name = "actor_id", length = 50)
    private String actorId;

    /** 기록 시점의 표시 이름. 계정이 사라져도 남는다. */
    @Column(name = "actor_name", length = 100)
    private String actorName;

    /** IPv6 를 문자열로 담을 수 있어야 하므로 45자다. */
    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Enumerated(EnumType.STRING)
    @Column(name = "audit_type", nullable = false, length = 40)
    private AuditType auditType;

    /** 대상 종류. 예: SKU, PRODUCT_MAPPING, CHANNEL. */
    @Column(name = "target_type", length = 50)
    private String targetType;

    @Column(name = "target_id", length = 100)
    private String targetId;

    /** 목록에 바로 보이는 한 줄. */
    @Column(nullable = false, length = 500)
    private String summary;

    /**
     * 변경 전 상태 (JSON 문자열).
     *
     * <p>HTML 을 넣지 않는다. 사내 DLPCenter 는 감사 상세를 HTML 로 저장하는데, 그 순간 DB 가
     * 저장형 XSS 창고가 된다. JSON 으로 담고 화면에서 이스케이프해 렌더링한다.
     */
    @Column(name = "before_snapshot", columnDefinition = "text")
    private String beforeSnapshot;

    @Column(name = "after_snapshot", columnDefinition = "text")
    private String afterSnapshot;

    @Column(name = "request_uri", length = 300)
    private String requestUri;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Builder(access = AccessLevel.PRIVATE)
    private AuditLog(Instant occurredAt, String actorId, String actorName, String clientIp,
                     AuditType auditType, String targetType, String targetId, String summary,
                     String beforeSnapshot, String afterSnapshot, String requestUri, String userAgent) {
        this.id = UuidV7.generate();
        this.occurredAt = occurredAt;
        this.actorId = actorId;
        this.actorName = actorName;
        this.clientIp = clientIp;
        this.auditType = auditType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.summary = summary;
        this.beforeSnapshot = beforeSnapshot;
        this.afterSnapshot = afterSnapshot;
        this.requestUri = requestUri;
        this.userAgent = userAgent;
    }

    /**
     * 기록을 만든다.
     *
     * <p>{@code summary} 가 비면 유형의 기본 문장을 쓴다. 목록의 "내용" 열이 비어 있으면 그 행은
     * 읽을 수 없는 기록이 되므로 빈 값을 허용하지 않는다.
     */
    public static AuditLog of(AuditRecord record) {
        String summary = record.summary() == null || record.summary().isBlank()
                ? record.auditType().defaultSummary()
                : record.summary();

        return AuditLog.builder()
                .occurredAt(record.occurredAt())
                .actorId(truncate(record.actorId(), 50))
                .actorName(truncate(record.actorName(), 100))
                .clientIp(truncate(record.clientIp(), 45))
                .auditType(record.auditType())
                .targetType(truncate(record.targetType(), 50))
                .targetId(truncate(record.targetId(), 100))
                .summary(truncate(summary, 500))
                .beforeSnapshot(record.beforeSnapshot())
                .afterSnapshot(record.afterSnapshot())
                .requestUri(truncate(record.requestUri(), 300))
                .userAgent(truncate(record.userAgent(), 300))
                .build();
    }

    public boolean hasSnapshot() {
        return beforeSnapshot != null || afterSnapshot != null;
    }

    /**
     * 길이를 잘라 담는다.
     *
     * <p>User-Agent 처럼 외부가 길이를 정하는 값이 있어서 그대로 넣으면 컬럼 길이 초과로 INSERT
     * 가 실패한다. <b>감사 기록이 길이 때문에 유실되는 것보다 잘려서라도 남는 편이 낫다.</b>
     */
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
