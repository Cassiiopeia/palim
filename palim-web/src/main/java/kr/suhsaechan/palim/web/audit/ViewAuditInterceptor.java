package kr.suhsaechan.palim.web.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 화면 조회를 감사 로그로 남긴다 (DLPCenter {@code AuthInterceptor} 개념).
 *
 * <h2>왜 인터셉터인가</h2>
 *
 * <p>컨트롤러마다 조회 기록을 호출하게 하면 새 화면을 추가할 때 빠뜨리고, <b>빠뜨린 화면은
 * 감사에서 사라진다.</b> 화면 추가를 감사 누락의 기회로 만들지 않으려면 횡단 관심사로 다뤄야
 * 한다.
 *
 * <h2>{@code afterCompletion} 에서 기록한다</h2>
 *
 * <p>{@code preHandle} 에 두면 권한 없는 요청이나 예외로 끝난 요청까지 "조회했다" 로 남는다.
 * 응답이 정상으로 끝난 요청만 기록한다.
 */
@RequiredArgsConstructor
public class ViewAuditInterceptor implements HandlerInterceptor {

    private final WebAuditRecorder webAuditRecorder;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        if (exception != null || !isAuditable(request, response)) {
            return;
        }

        String screenName = ScreenNames.of(request.getRequestURI());
        if (screenName == null) {
            return;
        }
        webAuditRecorder.recordView(screenName, request);
    }

    /**
     * 조회 감사 대상인지.
     *
     * <p>리다이렉트(3xx)는 제외한다. POST 후 리다이렉트로 돌아오는 GET 은 기록되지만, 로그인
     * 페이지로 튕겨나가는 요청까지 "조회" 로 남으면 안 된다.
     */
    private boolean isAuditable(HttpServletRequest request, HttpServletResponse response) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName());
    }
}
