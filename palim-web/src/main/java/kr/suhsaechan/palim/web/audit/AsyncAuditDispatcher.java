package kr.suhsaechan.palim.web.audit;

import kr.suhsaechan.palim.audit.AuditRecord;
import kr.suhsaechan.palim.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 조회 감사를 요청 스레드 밖에서 저장한다 (07-DECISIONS 018).
 *
 * <p>조회는 화면을 열 때마다 발생하므로 INSERT 지연이 모든 화면 응답에 얹힌다. 비동기로 빼면
 * 그 지연이 사라진다. 대신 프로세스가 죽는 순간의 조회 기록은 유실될 수 있다 — 조회는 그
 * 위험을 받아들이고, <b>인증·변경 기록은 동기로 남긴다.</b>
 *
 * <h2>{@code AuditRecord} 를 받는 이유</h2>
 *
 * <p>{@code HttpServletRequest} 를 비동기 스레드로 넘기면 안 된다. 응답이 끝나면 컨테이너가
 * 요청 객체를 재활용하므로, 다른 요청의 값이 읽히는 오염이 생긴다. 호출부가 요청 스레드에서
 * 값을 모두 뽑아 불변 레코드로 넘긴다.
 */
@Component
@RequiredArgsConstructor
public class AsyncAuditDispatcher {

    private final AuditService auditService;

    @Async
    public void dispatch(AuditRecord record) {
        auditService.record(record);
    }
}
