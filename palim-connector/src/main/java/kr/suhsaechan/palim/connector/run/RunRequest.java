package kr.suhsaechan.palim.connector.run;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 실행 요청.
 *
 * @param file      UPLOAD 원천일 때의 임시 파일. 다른 유형이면 {@code null}
 * @param headerRow 헤더 행 번호(1부터). 0 이하면 1로 본다
 */
public record RunRequest(UUID connectorId, RunMode mode, RunTrigger trigger, Path file,
                         int headerRow) {

    public RunRequest {
        if (headerRow <= 0) {
            headerRow = 1;
        }
    }

    public static RunRequest upload(UUID connectorId, RunMode mode, RunTrigger trigger, Path file) {
        return new RunRequest(connectorId, mode, trigger, file, 1);
    }
}
