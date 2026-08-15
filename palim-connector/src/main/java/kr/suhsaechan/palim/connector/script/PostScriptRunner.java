package kr.suhsaechan.palim.connector.script;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.excel.ConnectorScriptProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 사장님이 쓴 후처리 스크립트를 돌린다.
 *
 * <p><b>스크립트는 DB 를 모른다.</b> 자바가 행을 넘기고 결과를 받아 반영한다. 그래서 스크립트가
 * 아무리 잘못 짜여도 자료를 직접 망가뜨릴 수 없다 — 지우거나 다른 표를 건드리거나 트랜잭션을
 * 물고 늘어질 방법이 없다.
 *
 * <p>그리고 <b>표 이름을 모르므로 시험과 실제가 갈리지 않는다.</b> 어느 표에 담을지는 자바가
 * 안다. SQL 로 만들었다면 쿼리 안에 표 이름이 들어가 시험용과 실제용이 따로 생기고, 그 둘이
 * 어긋나는 순간 「시험에서는 되는데 실제로는 다른」 상태가 된다.
 *
 * <p>py 호출 규약(04-CONVENTIONS)을 지킨다 — 인자 배열 · stdout 은 JSON 만 ·
 * {@code PYTHONIOENCODING=utf-8} · 타임아웃 + {@code destroyForcibly}.
 *
 * <p><b>코드를 인자로 넘기지 않는다.</b> 임시 파일에 적고 그 경로를 넘긴다. 명령줄에는 길이
 * 한도가 있고, 코드에 든 따옴표·줄바꿈이 쉘을 거치며 깨진다.
 */
@Slf4j
@Component
public class PostScriptRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 사람이 남긴 말 중 보관할 길이. 전체를 담으면 화면과 로그가 그것으로 가득 찬다. */
    private static final int STDERR_LIMIT = 2000;

    private final ConnectorScriptProperties properties;

    public PostScriptRunner(ConnectorScriptProperties properties) {
        this.properties = properties;
    }

    /**
     * @param body     파이썬 원문
     * @param rows     매핑을 마친 행들. 스크립트가 이것을 받아 바꿀 칸만 돌려준다
     * @param timeoutMs 이 시간을 넘기면 강제로 끊는다
     */
    public PostScriptResult run(String body, List<Map<String, Object>> rows, long timeoutMs) {
        Path script = null;
        Path outFile = null;
        Path errFile = null;
        Process process = null;
        long started = System.nanoTime();
        try {
            script = Files.createTempFile("palim-post-", ".py");
            Files.writeString(script, body, StandardCharsets.UTF_8);
            outFile = Files.createTempFile("palim-post-out-", ".json");
            errFile = Files.createTempFile("palim-post-err-", ".txt");

            ProcessBuilder builder = new ProcessBuilder(
                    List.of(properties.pythonExecutable(), script.toString()));
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            // 출력을 파일로 돌린다.
            //
            // 스트림을 직접 읽으면 두 가지로 멈춘다. 다 읽을 때까지 기다리면 «끝나지 않는
            // 스크립트» 에서 타임아웃이 돌 기회조차 없고(실제로 그렇게 멈췄다), 안 읽으면
            // 파이프가 차서 스크립트 쪽이 멈춘다. 파일로 돌리면 둘 다 없다.
            builder.redirectOutput(outFile.toFile());
            builder.redirectError(errFile.toFile());

            process = builder.start();
            writeInput(process, rows);

            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                log.warn("후처리 스크립트 시간 초과 — {}ms 를 넘겼습니다. 행 {}건", timeoutMs, rows.size());
                return PostScriptResult.timedOut(trim(readFile(errFile)), elapsed(started));
            }

            String stderr = trim(readFile(errFile));
            if (process.exitValue() != 0) {
                // 사람용 메시지는 stderr 로 온다. 그것이 곧 화면에 보여줄 사유다.
                log.warn("후처리 스크립트 실패 (exit {}) — 남긴 말={}", process.exitValue(), stderr);
                return PostScriptResult.failed(
                        stderr == null ? "스크립트가 오류로 끝났습니다." : stderr, elapsed(started));
            }
            return parse(readFile(outFile), stderr, elapsed(started));

        } catch (IOException e) {
            log.warn("후처리 스크립트를 실행하지 못했습니다", e);
            return PostScriptResult.failed("스크립트를 실행하지 못했습니다: " + e.getMessage(),
                    elapsed(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PostScriptResult.failed("실행이 중단됐습니다", elapsed(started));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteQuietly(script);
            deleteQuietly(outFile);
            deleteQuietly(errFile);
        }
    }

    private static String readFile(Path path) {
        try {
            return path == null ? "" : Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("스크립트 출력을 읽지 못했습니다 — {}", path);
            return "";
        }
    }

    /** 행을 stdin 으로 넘긴다. 인자로 넘기면 길이 한도에 걸린다. */
    private void writeInput(Process process, List<Map<String, Object>> rows) throws IOException {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("rows", rows);
        try (OutputStream out = process.getOutputStream()) {
            out.write(MAPPER.writeValueAsBytes(input));
        }
    }

    /**
     * 돌려받은 것을 읽는다.
     *
     * <p>스크립트가 JSON 이 아닌 것을 뱉으면 <b>실패로 본다.</b> 반쯤 읽어 반영하면 어떤 행은
     * 다듬어지고 어떤 행은 아닌 상태가 되는데, 그것이 가장 찾기 어려운 고장이다.
     */
    private PostScriptResult parse(String stdout, String stderr, long elapsedMs) {
        if (stdout.isBlank()) {
            return PostScriptResult.failed(
                    "스크립트가 아무것도 돌려주지 않았습니다. 마지막에 print 로 JSON 을 내보내야 합니다.",
                    elapsedMs);
        }
        try {
            JsonNode root = MAPPER.readTree(stdout);
            JsonNode rows = root.path("rows");
            if (!rows.isArray()) {
                return PostScriptResult.failed(
                        "돌려준 것에 rows 배열이 없습니다. {\"rows\": [...]} 모양이어야 합니다.",
                        elapsedMs);
            }
            List<Map<String, Object>> parsed = new ArrayList<>();
            for (JsonNode row : rows) {
                if (!row.isObject()) {
                    continue;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                row.properties().forEach(entry ->
                        values.put(entry.getKey(), value(entry.getValue())));
                parsed.add(values);
            }
            return PostScriptResult.succeeded(parsed, stderr, elapsedMs);

        } catch (RuntimeException e) {
            // 스크립트가 stdout 에 사람용 메시지를 섞은 경우가 가장 흔하다. 그 사실을 짚어 준다.
            log.warn("후처리 스크립트가 JSON 이 아닌 것을 돌려줬습니다 — 앞부분={}", head(stdout), e);
            return PostScriptResult.failed(
                    "돌려준 것을 JSON 으로 읽지 못했습니다. print 는 마지막 JSON 하나만 하고, "
                            + "사람에게 할 말은 stderr 로 보내세요.", elapsedMs);
        }
    }

    /** JSON 값을 자바 값으로. 형 변환은 하지 않는다 — 그것은 변환 엔진이 이미 한 일이다. */
    private static Object value(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.isValueNode() ? node.asString() : node.toString();
    }

    private static String trim(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return null;
        }
        String trimmed = stderr.strip();
        return trimmed.length() <= STDERR_LIMIT
                ? trimmed
                : trimmed.substring(0, STDERR_LIMIT) + "\n… (이후 생략)";
    }

    private static String head(String stdout) {
        return stdout.length() <= 200 ? stdout : stdout.substring(0, 200) + "…";
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("임시 스크립트 파일을 지우지 못했습니다 — {}", path);
        }
    }

    /** 쓰지 않지만 규약상 남겨 둔다 — 이 클래스가 던지는 예외 유형을 한눈에 보이게. */
    @SuppressWarnings("unused")
    private static BusinessException unreachable() {
        return new BusinessException(ErrorCode.HOOK_TIMEOUT);
    }
}
