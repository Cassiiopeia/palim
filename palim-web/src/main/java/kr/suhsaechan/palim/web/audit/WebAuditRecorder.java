package kr.suhsaechan.palim.web.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import kr.suhsaechan.palim.audit.AuditRecord;
import kr.suhsaechan.palim.audit.AuditService;
import kr.suhsaechan.palim.audit.AuditType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * HTTP 요청 정보를 감사 로그로 옮긴다.
 *
 * <p>{@code palim-audit} 은 HTTP 를 모른다. actor · IP · User-Agent · 요청 URI 를 요청
 * 컨텍스트에서 뽑는 일은 화면 계층의 책임이고, 이 클래스가 그 경계다.
 *
 * <h2>기록 실패가 본 작업을 막지 않는다</h2>
 *
 * <p>{@link AuditService#record} 가 별도 트랜잭션에서 예외를 삼킨다. 이 클래스는 컨텍스트를
 * 채우는 동안에도 예외를 내지 않아야 한다 — 요청 컨텍스트가 없는 상황(스케줄러 등)에서도
 * 호출될 수 있다.
 */
@Component
@RequiredArgsConstructor
public class WebAuditRecorder {

    private final AuditService auditService;
    private final AuditSnapshots auditSnapshots;
    private final AsyncAuditDispatcher asyncAuditDispatcher;

    /**
     * 변경 작업을 기록한다.
     *
     * <p>actor 와 요청 정보는 현재 요청 컨텍스트에서 채운다.
     *
     * @param before 변경 전 상태. 등록처럼 이전 상태가 없으면 {@code null}
     * @param after  변경 후 상태
     */
    public void recordChange(AuditType auditType, String targetType, String targetId,
                             String summary, Map<String, ?> before, Map<String, ?> after) {
        HttpServletRequest request = currentRequest();

        auditService.record(AuditRecord.of(auditType)
                .actor(currentActorId(), null)
                .clientIp(ClientIpResolver.resolve(request))
                .target(targetType, targetId)
                .summary(summary)
                .snapshot(auditSnapshots.toJson(before), auditSnapshots.toJson(after))
                .request(requestUri(request), userAgent(request))
                .build());
    }

    /** 대상이 없는 변경(설정 등). */
    public void recordChange(AuditType auditType, String summary,
                             Map<String, ?> before, Map<String, ?> after) {
        recordChange(auditType, null, null, summary, before, after);
    }

    /**
     * 조회를 기록한다. <b>저장은 비동기다</b>(07-DECISIONS 018).
     *
     * <p>값 추출은 이 스레드에서 끝낸다 — 요청 객체는 응답 후 재활용되므로 비동기 스레드로
     * 넘길 수 없다. 화면 이름을 내용에 붙인다. URI 만 남기면 나중에 경로를 바꿨을 때 과거
     * 기록을 읽을 수 없게 된다.
     */
    public void recordView(String screenName, HttpServletRequest request) {
        asyncAuditDispatcher.dispatch(AuditRecord.of(AuditType.VIEW)
                .actor(currentActorId(), null)
                .clientIp(ClientIpResolver.resolve(request))
                .summary(screenName + " 을(를) 조회했습니다.")
                .request(requestUri(request), userAgent(request))
                .build());
    }

    /**
     * 인증 관련 사건을 기록한다.
     *
     * <p>actor 를 인자로 받는다. 로그인 실패 시점에는 {@code SecurityContext} 가 비어 있고,
     * <b>입력된 아이디를 남겨야</b> 어떤 계정을 노렸는지 알 수 있다.
     */
    public void recordAuth(AuditType auditType, String actorId, HttpServletRequest request) {
        recordAuth(auditType, actorId, request, null);
    }

    public void recordAuth(AuditType auditType, String actorId,
                           HttpServletRequest request, String summary) {
        auditService.record(AuditRecord.of(auditType)
                .actor(actorId, null)
                .clientIp(ClientIpResolver.resolve(request))
                .summary(summary)
                .request(requestUri(request), userAgent(request))
                .build());
    }

    // ------------------------------------------------------------------
    // 요청 컨텍스트
    // ------------------------------------------------------------------

    private String currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getName();
    }

    /**
     * 현재 요청. 없으면 {@code null}.
     *
     * <p>스케줄러에서 호출될 수 있으므로 요청이 없는 상황을 정상으로 다룬다.
     */
    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private static String requestUri(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    private static String userAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }
}
