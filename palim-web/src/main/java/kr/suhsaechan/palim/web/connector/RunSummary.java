package kr.suhsaechan.palim.web.connector;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 실행 이력 한 줄.
 *
 * <p>{@code mappingVersion} 을 보여주는 이유는 "지난달 데이터가 왜 이런가"에 답하기 위해서다.
 * 정의를 바꿔도 과거 실행이 어느 버전으로 돌았는지 남는다.
 */
public record RunSummary(UUID id, String runMode, String triggerType, String status,
                         int mappingVersion, int totalCount, int successCount, int failedCount,
                         Instant startedAt, Instant finishedAt, String errorSummary) {

    public boolean isTest() {
        return "TEST".equals(runMode);
    }

    public boolean rolledBack() {
        return "ROLLED_BACK".equals(status);
    }

    /** 소요 시간(초). 아직 안 끝났으면 {@code null}. */
    public Long elapsedSeconds() {
        return finishedAt == null ? null : Duration.between(startedAt, finishedAt).toSeconds();
    }
}
