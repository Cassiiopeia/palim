package kr.suhsaechan.palim.connector.run;

import java.nio.file.Path;
import java.time.LocalDate;
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
                         int headerRow, boolean skipPostScripts, LocalDate baseDate) {

    /**
     * @param baseDate 이 자료가 «며칟날» 기준인가. 비우면 오늘로 본다.
     *
     *                 <p>파일로 대신 채울 때 필요하다. 자동 수집은 지금 물어보므로 오늘 것이
     *                 맞지만, <b>사람이 받아 오는 파일은 어제 것일 수 있다.</b> 자동 수집이
     *                 어제 멈췄다면 어제 기준으로 조회해 받는 것이 정상이고, 그때 이 값이
     *                 없으면 어제 자료가 «오늘 자료인 척» 담긴다.
     *
     *                 <p>그러면 대조는 <b>없는 차이를 있다고 말한다</b> — 어제 재고와 오늘
     *                 재고를 견주게 되기 때문이다. 사람은 그것이 날짜 때문인 줄 모르고 창고를
     *                 뒤진다.
     */
    public RunRequest {
        if (headerRow <= 0) {
            headerRow = 1;
        }
    }

    public RunRequest(UUID connectorId, RunMode mode, RunTrigger trigger, Path file,
                      int headerRow, boolean skipPostScripts) {
        this(connectorId, mode, trigger, file, headerRow, skipPostScripts, null);
    }

    public RunRequest(UUID connectorId, RunMode mode, RunTrigger trigger, Path file,
                      int headerRow) {
        this(connectorId, mode, trigger, file, headerRow, false, null);
    }

    public static RunRequest upload(UUID connectorId, RunMode mode, RunTrigger trigger, Path file) {
        return new RunRequest(connectorId, mode, trigger, file, 1);
    }
}
