package kr.suhsaechan.palim.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 발주자 조치가 필요한 사건 (#34).
 *
 * <p>알림은 흘러가면 끝이지만 인시던트는 <b>처리 여부를 추적한다.</b> "그 오버셀 처리했던가?"
 * 를 기억이 아니라 시스템이 답한다.
 *
 * <h2>재발은 미해결 인시던트에 누적한다</h2>
 *
 * <p>같은 SKU 오버셀이 주문마다 새 인시던트가 되면 목록이 스팸이 되어 알림과 똑같은 실패를
 * 반복한다. 같은 {@code dedupeKey} 의 미해결 인시던트가 있으면 발생 횟수와 최근 발생 시각만
 * 갱신한다. <b>해결된 뒤 재발하면 새 인시던트다</b> — 해결 이력을 덮어쓰면 "언제 무엇을
 * 해결했는지"가 사라진다.
 */
@Getter
@Entity
@Table(name = "incident")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident extends BaseTimeEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "incident_type", nullable = false, length = 30)
    private IncidentType incidentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentStatus status;

    /** 목록에 보이는 한 줄. 예: "SKU-001 초과판매 — 재고 -2". */
    @Column(nullable = false, length = 300)
    private String title;

    /**
     * 상세. 사람이 읽는 여러 줄 텍스트다.
     *
     * <p>JSON 으로 두지 않는다 — 보고 지점마다 손으로 JSON 을 조립하면 값에 따옴표가 들어오는
     * 순간 깨지고, 발주자가 읽을 대상에 구조화가 필요하지도 않다. 화면은 이스케이프 렌더링만
     * 한다.
     */
    @Column(columnDefinition = "text")
    private String detail;

    /**
     * 중복 방지 키. {@code {유형}:{대상식별자}} 형식.
     *
     * <p>미해결 인시던트 조회에 쓴다. DB 유니크 제약은 걸지 않는다 — 해결된 같은 키의 행이
     * 여럿 존재하는 것이 정상이다(재발 이력).
     */
    @Column(name = "dedupe_key", nullable = false, length = 200)
    private String dedupeKey;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    @Column(name = "first_occurred_at", nullable = false, updatable = false)
    private Instant firstOccurredAt;

    @Column(name = "last_occurred_at", nullable = false)
    private Instant lastOccurredAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** 해결 시 필수. "무엇을 했는지" 없는 해결은 추적이 아니다. */
    @Column(name = "resolution_memo", length = 1000)
    private String resolutionMemo;

    @Version
    private Long version;

    private Incident(IncidentType incidentType, String title, String detail,
                     String dedupeKey, Instant occurredAt) {
        this.id = UuidV7.generate();
        this.incidentType = incidentType;
        this.status = IncidentStatus.OPEN;
        this.title = title;
        this.detail = detail;
        this.dedupeKey = dedupeKey;
        this.occurrenceCount = 1;
        this.firstOccurredAt = occurredAt;
        this.lastOccurredAt = occurredAt;
    }

    public static Incident open(IncidentType incidentType, String title, String detail,
                                String dedupeKey, Instant occurredAt) {
        if (dedupeKey == null || dedupeKey.isBlank()) {
            throw new IllegalArgumentException("dedupeKey 는 필수다 — 재발 누적의 기준이다");
        }
        return new Incident(incidentType, title, detail, dedupeKey, occurredAt);
    }

    /**
     * 같은 사건이 다시 발생했다.
     *
     * <p>제목·상세를 최신으로 갱신한다 — 오버셀 재고가 -2 에서 -5 로 악화됐으면 목록에
     * 최신 상태가 보여야 한다.
     */
    public void recordRecurrence(String title, String detail, Instant occurredAt) {
        if (!status.isUnresolved()) {
            throw new BusinessException(ErrorCode.INCIDENT_ALREADY_RESOLVED, id);
        }
        this.occurrenceCount++;
        this.lastOccurredAt = occurredAt;
        this.title = title;
        this.detail = detail;
    }

    /** 확인 — "봤다". 처리 중임을 표시한다. */
    public void acknowledge(Instant now) {
        if (status != IncidentStatus.OPEN) {
            throw new BusinessException(ErrorCode.INCIDENT_INVALID_TRANSITION, status);
        }
        this.status = IncidentStatus.ACKNOWLEDGED;
        this.acknowledgedAt = now;
    }

    /**
     * 해결. {@code OPEN → RESOLVED} 직행을 허용한다 — 사소한 건을 두 번 클릭시킬 이유가 없다.
     */
    public void resolve(String memo, Instant now) {
        if (status == IncidentStatus.RESOLVED) {
            throw new BusinessException(ErrorCode.INCIDENT_ALREADY_RESOLVED, id);
        }
        if (memo == null || memo.isBlank()) {
            throw new BusinessException(ErrorCode.INCIDENT_MEMO_REQUIRED);
        }
        this.status = IncidentStatus.RESOLVED;
        this.resolvedAt = now;
        this.resolutionMemo = memo.trim();
    }

    public boolean isUnresolved() {
        return status.isUnresolved();
    }
}
