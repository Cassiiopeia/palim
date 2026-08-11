package kr.suhsaechan.palim.automation.influencer.transcript;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code scripts/youtube_transcript.py} 호출.
 *
 * <p>py 호출 규약(04-CONVENTIONS)을 그대로 지킨다:
 * <ul>
 *   <li><b>인자 배열</b> — 영상 ID 는 외부 입력이라 쉘 문자열로 조립하면 커맨드 인젝션이 된다</li>
 *   <li><b>stdout JSON only</b> — 사람용 메시지는 stderr 로 분리해 파싱을 깨뜨리지 않는다</li>
 *   <li><b>{@code PYTHONIOENCODING=utf-8} + 읽기 UTF-8 명시</b> — 한글 자막이 깨지는 원인이다</li>
 *   <li><b>타임아웃 + {@code destroyForcibly}</b> — 좀비 프로세스가 쌓이면 서버가 죽는다</li>
 * </ul>
 *
 * <p>동시 실행 제한은 호출자({@code AiReviewService})가 전용 스레드풀로 건다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YtDlpTranscriptProvider implements TranscriptProvider {

    private static final String SCRIPT = "youtube_transcript.py";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScriptProperties scriptProperties;

    @Override
    public TranscriptResult fetch(String youtubeVideoId) {
        Path script = Path.of(scriptProperties.directory(), SCRIPT);

        ProcessBuilder builder = new ProcessBuilder(List.of(
                scriptProperties.pythonExecutable(), script.toString(), youtubeVideoId));
        // Windows 개발 환경의 cp949 로 인해 한글이 깨지는 것을 막는다.
        builder.environment().put("PYTHONIOENCODING", "utf-8");

        Process process = null;
        try {
            process = builder.start();
            String stdout = read(process);

            if (!process.waitFor(scriptProperties.timeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("자막 추출 시간 초과 — {}", youtubeVideoId);
                return TranscriptResult.blocked();
            }

            if (process.exitValue() != 0) {
                log.warn("자막 추출 실패 (exit {}) — {}", process.exitValue(), youtubeVideoId);
                return TranscriptResult.blocked();
            }
            return parse(stdout, youtubeVideoId);

        } catch (IOException e) {
            log.warn("자막 스크립트 실행 실패 — {}", youtubeVideoId, e);
            return TranscriptResult.blocked();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TranscriptResult.blocked();

        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String read(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().reduce("", (a, b) -> a + b);
        }
    }

    private TranscriptResult parse(String stdout, String youtubeVideoId) {
        try {
            JsonNode node = MAPPER.readTree(stdout);
            TranscriptStatus status = TranscriptStatus.valueOf(
                    node.path("status").asString("BLOCKED"));
            return new TranscriptResult(status,
                    node.path("language").asString(null),
                    node.path("content").asString(null));

        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("자막 스크립트 출력 파싱 실패 — {}", youtubeVideoId, e);
            return TranscriptResult.blocked();
        }
    }
}
