package kr.suhsaechan.palim.connector.script;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스크립트 실행 기록을 <b>따로 떼어 남긴다.</b>
 *
 * <p>스크립트가 죽으면 실행 전체를 세운다. 그런데 실패 기록을 같은 트랜잭션에서 남기면
 * <b>그 기록도 함께 되돌아가</b> 아무것도 남지 않는다. 실패를 설명하려고 만든 기록이 정작
 * 실패했을 때만 사라지는 셈이다.
 *
 * <p>그래서 별도 트랜잭션으로 커밋한다. 자료를 담는 일은 되돌아가되, <b>무슨 일이 있었는지는
 * 남는다.</b>
 */
@Component
@RequiredArgsConstructor
public class PostScriptRunRecorder {

    private final PostScriptRunRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSucceeded(UUID tenantId, PostScript script, UUID connectorRunId,
                                int total, int changed, PostScriptResult result) {
        repository.save(PostScriptRun.succeeded(tenantId, script, connectorRunId,
                total, changed, result));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailed(UUID tenantId, PostScript script, UUID connectorRunId,
                             int total, PostScriptResult result) {
        repository.save(PostScriptRun.failed(tenantId, script, connectorRunId, total, result));
    }
}
