package kr.suhsaechan.palim.audit;

import java.time.Instant;

/**
 * 감사 기록 요청.
 *
 * <p>{@link AuditService#record(AuditRecord)} 의 입력이다. 필드가 11개라 위치 인자로 넘기면
 * 순서를 틀린 호출이 컴파일을 통과한다({@code actorId} 와 {@code actorName} 은 둘 다
 * {@code String} 이다). 빌더로만 만들게 해서 그 실수를 막는다.
 *
 * <p>{@code occurredAt} 은 기록 시점에 서버가 채운다. 호출부가 넘기지 않으면 {@code build()} 가
 * 현재 시각을 넣는다.
 */
public record AuditRecord(
        Instant occurredAt,
        AuditType auditType,
        String actorId,
        String actorName,
        String clientIp,
        String targetType,
        String targetId,
        String summary,
        String beforeSnapshot,
        String afterSnapshot,
        String requestUri,
        String userAgent
) {

    public AuditRecord {
        if (auditType == null) {
            throw new IllegalArgumentException("auditType 은 필수다");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt 은 필수다");
        }
    }

    public static Builder of(AuditType auditType) {
        return new Builder(auditType);
    }

    public static final class Builder {

        private final AuditType auditType;
        private Instant occurredAt;
        private String actorId;
        private String actorName;
        private String clientIp;
        private String targetType;
        private String targetId;
        private String summary;
        private String beforeSnapshot;
        private String afterSnapshot;
        private String requestUri;
        private String userAgent;

        private Builder(AuditType auditType) {
            this.auditType = auditType;
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public Builder actor(String actorId, String actorName) {
            this.actorId = actorId;
            this.actorName = actorName;
            return this;
        }

        public Builder clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        public Builder target(String targetType, String targetId) {
            this.targetType = targetType;
            this.targetId = targetId;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        /** 변경 전·후 상태. JSON 문자열을 넘긴다. */
        public Builder snapshot(String beforeSnapshot, String afterSnapshot) {
            this.beforeSnapshot = beforeSnapshot;
            this.afterSnapshot = afterSnapshot;
            return this;
        }

        public Builder request(String requestUri, String userAgent) {
            this.requestUri = requestUri;
            this.userAgent = userAgent;
            return this;
        }

        public AuditRecord build() {
            return new AuditRecord(
                    occurredAt != null ? occurredAt : Instant.now(),
                    auditType, actorId, actorName, clientIp,
                    targetType, targetId, summary,
                    beforeSnapshot, afterSnapshot, requestUri, userAgent);
        }
    }
}
