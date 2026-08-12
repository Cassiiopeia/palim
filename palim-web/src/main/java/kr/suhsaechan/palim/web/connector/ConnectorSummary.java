package kr.suhsaechan.palim.web.connector;

import java.time.Instant;
import java.util.UUID;

/**
 * 커넥터 목록 한 줄.
 *
 * <p>목록을 훑을 때 <b>문제가 먼저 보여야 한다.</b> 마지막 실행 상태와 실패 건수를 함께 담아
 * 화면이 추가 조회 없이 경고를 표시할 수 있게 한다.
 *
 * @param lastStatus  마지막 실행 상태. 한 번도 안 돌았으면 {@code null}
 * @param activeVersion 확정된 매핑 버전. 없으면 아직 LIVE 실행이 불가능하다
 */
public record ConnectorSummary(UUID id, String code, String name, String targetModelName,
                               String sourceType, boolean enabled, Integer activeVersion,
                               String lastStatus, Instant lastRunAt, int lastSuccess,
                               int lastFailed) {

    /** 확정 매핑이 없으면 테스트만 가능하다. 목록에서 이를 구분해 보여준다. */
    public boolean readyForLive() {
        return activeVersion != null;
    }

    public boolean hasFailure() {
        return lastFailed > 0 || "FAILED".equals(lastStatus);
    }
}
