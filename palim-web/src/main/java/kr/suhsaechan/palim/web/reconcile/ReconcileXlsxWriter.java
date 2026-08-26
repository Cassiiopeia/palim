package kr.suhsaechan.palim.web.reconcile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 대조 결과를 <b>엑셀 파일</b>로 만든다.
 *
 * <h2>왜 CSV 가 아닌가</h2>
 *
 * <p>엑셀은 CSV 를 읽을 때 값을 숫자로 해석한다. 그러면 품목코드 {@code 00094} 가 {@code 94}
 * 로, {@code 01002} 가 {@code 1,002} 로 바뀐다. <b>이 제품의 핵심이 그 코드를 견주는 것이므로,
 * 내려받은 파일에서 코드가 달라지면 그 파일로는 아무것도 못 맞춘다.</b>
 *
 * <p>같은 함정을 화면에서 이미 겪었다 — 숫자처럼 「생긴」 값을 다듬어 품목코드가 바뀌어 보였다.
 *
 * <h2>왜 파이썬인가</h2>
 *
 * <p>엑셀을 쓰는 라이브러리가 이미 배포 이미지에 들어 있다(파일을 <b>읽는</b> 데 쓰고 있다).
 * 자바 쪽 라이브러리를 새로 더하면 같은 일을 하는 것이 둘이 된다.
 *
 * <h2>내용을 인자가 아니라 파일로 넘기는 이유</h2>
 *
 * <p>결과가 수천 줄이 될 수 있는데, 명령행 길이 상한을 넘으면 그때부터 <b>조용히 잘린다.</b>
 * 잘린 파일은 「틀린 파일」 이 아니라 「짧은 파일」 이라 사람이 알아채지 못한다.
 */
@Slf4j
@Component
public class ReconcileXlsxWriter {

    private static final String SCRIPT = "write_reconcile_xlsx.py";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String pythonExecutable;
    private final String scriptDirectory;
    private final int timeoutSeconds;

    public ReconcileXlsxWriter(
            @Value("${palim.scripts.python-executable:python3}") String pythonExecutable,
            @Value("${palim.scripts.directory:scripts}") String scriptDirectory,
            @Value("${palim.scripts.timeout-seconds:30}") int timeoutSeconds) {
        this.pythonExecutable = pythonExecutable;
        this.scriptDirectory = scriptDirectory;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 엑셀 한 벌을 만들어 <b>바이트로</b> 돌려준다.
     *
     * <p>바이트로 돌려주고 임시 파일을 곧바로 지운다. 응답이 나간 뒤에 지우려면 그 시점을
     * 붙들어야 하는데, 붙들다 놓치면 파일이 서버에 쌓인다.
     *
     * @param summary 「무엇을 견줬나」. 며칠 뒤에 열어도 화면과 짝지을 수 있어야 한다
     * @param sheets  결과 갈래별 한 장씩
     */
    public byte[] write(List<Line> summary, List<Sheet> sheets) {
        Path input = null;
        Path output = null;
        try {
            input = Files.createTempFile("reconcile-", ".json");
            output = Files.createTempFile("reconcile-", ".xlsx");
            Files.writeString(input, MAPPER.writeValueAsString(
                    Map.of("summary", summary, "sheets", sheets)), StandardCharsets.UTF_8);

            run(input, output);
            return Files.readAllBytes(output);

        } catch (IOException e) {
            log.warn("대조 결과 엑셀을 만들지 못했다", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        } finally {
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    private void run(Path input, Path output) throws IOException {
        // 인자 배열로 넘긴다. 쉘 문자열을 조립하면 경로에 공백·따옴표가 있을 때 깨지고,
        // 그 경로가 사용자 입력에서 오는 순간 명령이 주입된다.
        ProcessBuilder builder = new ProcessBuilder(List.of(
                pythonExecutable, Path.of(scriptDirectory, SCRIPT).toString(),
                input.toString(), output.toString()));
        // 개발 환경의 cp949 로 한글이 깨지는 것을 막는다.
        builder.environment().put("PYTHONIOENCODING", "utf-8");

        Process process = null;
        try {
            process = builder.start();
            // stdout 을 비우지 않으면 버퍼가 차서 스크립트가 그 자리에 멈춘다.
            String stdout = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                // 좀비가 쌓이면 서버가 죽는다.
                process.destroyForcibly();
                log.warn("대조 결과 엑셀 만들기 시간 초과");
                throw new BusinessException(ErrorCode.HOOK_TIMEOUT);
            }
            if (process.exitValue() != 0) {
                // 사람용 메시지는 stderr 로 갔다.
                log.warn("대조 결과 엑셀 만들기 실패 (exit {})", process.exitValue());
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            log.debug("대조 결과 엑셀 완료 — {}", stdout);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 못 지워도 응답은 나가야 한다. 임시 디렉터리는 결국 정리된다.
            log.debug("임시 파일을 지우지 못했다 — {}", path);
        }
    }

    /** 「무엇을 견줬나」 한 줄. */
    public record Line(String label, String value) {
    }

    /**
     * 결과 한 장.
     *
     * @param textColumns 앞에서 몇 칸을 <b>글자로 고정</b>할지. 품목코드가 숫자로 바뀌는 것을 막는다
     */
    public record Sheet(String title, List<String> columns, int textColumns,
                        List<Map<String, String>> rows) {
    }
}
