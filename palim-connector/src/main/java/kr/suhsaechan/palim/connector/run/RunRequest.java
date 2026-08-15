package kr.suhsaechan.palim.connector.run;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 실행 요청.
 *
 * @param file      UPLOAD 원천일 때의 임시 파일. 다른 유형이면 {@code null}
 * @param headerRow 헤더 행 번호(1부터). 0 이하면 1로 본다
 * @param skipPostScripts 후처리 스크립트를 건너뛴다. 스크립트를 거치기 «전» 모습과 대보려고
 *                        시험 실행 화면이 켠다
 */
public record RunRequest(UUID connectorId, RunMode mode, RunTrigger trigger, Path file,
                         int headerRow, boolean skipPostScripts) {

    public RunRequest {
        if (headerRow <= 0) {
            headerRow = 1;
        }
    }

    public RunRequest(UUID connectorId, RunMode mode, RunTrigger trigger, Path file,
                      int headerRow) {
        this(connectorId, mode, trigger, file, headerRow, false);
    }

    public static RunRequest upload(UUID connectorId, RunMode mode, RunTrigger trigger, Path file) {
        return new RunRequest(connectorId, mode, trigger, file, 1);
    }
}
