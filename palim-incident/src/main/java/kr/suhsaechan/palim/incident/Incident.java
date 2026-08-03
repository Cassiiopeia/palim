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
 * 인시던트 — 사람이 마감해야 하는 문제 기록 (#35).
 *
 * <p>미해결(OPEN·ACKNOWLEDGED) 상태의 같은 문제는 행을 늘리지 않고 발생 횟수만 누적한다.
 * 정합성 불일치는 해결 전까지 매 주기 다시 감지되므로, 발생마다 행을 만들면 목록이 같은
 * 문제로 도배되어 정작 봐야 할 건이 묻힌다. 부분 유니크 인덱스
 * ({@code ux_incident_open_dedupe})가 이 규칙의 최종 방어선이다.
 */
@Getter
@Entity
@Table(name = "incident")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident extends BaseTimeEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IncidentType type;

    /** 동일 문제 판정 키. {@code {유형}:{대상식별자}} — Outbox 억제 키와 같은 형식이다. */
    @Column(nullable = false, length = 200)
    private String dedupeKey;

    /** 목록 표시용 한 줄. */
    @Column(nullable = false, length = 200)
    private String title;

    /** 상세 문맥. 재발 시 최신 상태로 갱신한다 — 조치에 필요한 것은 최근 값이다. */
    @Column(columnDefinition = "text")
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentStatus status;

    /** 발생 횟수. 재발 시 +1. */
    @Column(nullable = false)
    private int occurrenceCount;

    /** 최근 발생 시각. 최초 발생은 {@code createdAt} 이 담당한다. */
    @Column(nullable = false)
    private Instant lastOccurredAt;

    private Instant acknowledgedAt;

    private Instant resolvedAt;

    /** 해결 메모 — "왜 발생했고 어떻게 조치했는지". 선택 입력. */
    @Column(length = 1000)
    private String resolutionNote;

    /** 화면 이중 클릭·동시 수정 방어. */
    @Version
    private Long version;

    private Incident(IncidentType type, String dedupeKey, String title, String detail,
                     Instant occurredAt) {
        this.id = UuidV7.generate();
        this.type = type;
        this.dedupeKey = dedupeKey;
        this.title = title;
        this.detail = detail;
        this.status = IncidentStatus.OPEN;
        this.occurrenceCount = 1;
        this.lastOccurredAt = occurredAt;
    }

    public static Incident open(IncidentType type, String dedupeKey, String title, String detail,
                                Instant occurredAt) {
        return new Incident(type, dedupeKey, title, detail, occurredAt);
    }

    /**
     * 재발을 누적한다.
     *
     * <p>상태는 바꾸지 않는다 — ACKNOWLEDGED 에서 재발해도 이미 인지한 문제이므로
     * 미확인으로 되돌리면 확인 표시가 무의미해진다.
     */
    public void recordRecurrence(String detail, Instant occurredAt) {
        this.occurrenceCount++;
        this.lastOccurredAt = occurredAt;
        this.detail = detail;
    }

    /** 미확인 → 확인. 그 외 상태에서는 거부한다. */
    public void acknowledge() {
        if (status != IncidentStatus.OPEN) {
            throw new BusinessException(ErrorCode.INCIDENT_STATUS_INVALID, status.displayName());
        }
        this.status = IncidentStatus.ACKNOWLEDGED;
        this.acknowledgedAt = Instant.now();
    }

    /**
     * 해결 처리. OPEN 에서 직행도 허용한다 — 1인 운영에서 확인 클릭 강제는 수고만 늘린다.
     */
    public void resolve(String resolutionNote) {
        if (status == IncidentStatus.RESOLVED) {
            throw new BusinessException(ErrorCode.INCIDENT_STATUS_INVALID, status.displayName());
        }
        this.status = IncidentStatus.RESOLVED;
        this.resolvedAt = Instant.now();
        this.resolutionNote = resolutionNote;
    }

    public boolean isResolved() {
        return status == IncidentStatus.RESOLVED;
    }
}
