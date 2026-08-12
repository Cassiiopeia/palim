package kr.suhsaechan.palim.connector.excel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code scripts/parse_stock_excel.py} 호출.
 *
 * <p>py 호출 규약(04-CONVENTIONS)을 그대로 지킨다: <b>인자 배열</b> · <b>stdout JSON only</b> ·
 * {@code PYTHONIOENCODING=utf-8} · <b>타임아웃 + {@code destroyForcibly}</b>.
 *
 * <p>파일 경로를 인자 배열로 넘기는 이유는 쉘 문자열을 조립하면 파일명에 공백·따옴표가 있을 때
 * 깨지고, 그 경로가 사용자 입력에서 오는 순간 커맨드 인젝션이 되기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExcelParser {

    private static final String SCRIPT = "parse_stock_excel.py";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConnectorScriptProperties properties;

    /**
     * 파일을 읽어 표준 형태로 돌려준다.
     *
     * @param headerRow 헤더 행 번호(1부터). 위쪽 제목 행을 건너뛴다
     * @param limit     0이면 전체, 양수면 미리보기 건수. 전체 건수는 항상 그대로 보고된다
     */
    public ExcelParseResult parse(Path file, int headerRow, int limit) {
        List<String> command = buildCommand(file, headerRow, limit);

        ProcessBuilder builder = new ProcessBuilder(command);
        // Windows 개발 환경의 cp949 로 한글 헤더가 깨지는 것을 막는다.
        builder.environment().put("PYTHONIOENCODING", "utf-8");

        Process process = null;
        try {
            process = builder.start();
            String stdout = readAll(process);

            if (!process.waitFor(properties.timeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("엑셀 파싱 시간 초과 — {}", file);
                throw new BusinessException(ErrorCode.HOOK_TIMEOUT);
            }
            if (process.exitValue() != 0) {
                // 사람용 메시지는 stderr 로 갔다. 여기서는 파일명만 남긴다.
                log.warn("엑셀 파싱 실패 (exit {}) — {}", process.exitValue(), file);
                throw new BusinessException(ErrorCode.CONNECTOR_SOURCE_UNREACHABLE,
                        fileName(file));
            }
            return toResult(stdout, file);

        } catch (IOException e) {
            log.warn("엑셀 파싱 실행 실패 — {}", file, e);
            throw new BusinessException(ErrorCode.CONNECTOR_SOURCE_UNREACHABLE, fileName(file));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.CONNECTOR_SOURCE_UNREACHABLE, fileName(file));

        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private List<String> buildCommand(Path file, int headerRow, int limit) {
        Path script = Path.of(properties.directory(), SCRIPT);

        List<String> command = new ArrayList<>(List.of(
                properties.pythonExecutable(), script.toString(), file.toString(),
                "--header-row", String.valueOf(headerRow)));
        if (limit > 0) {
            command.add("--limit");
            command.add(String.valueOf(limit));
        }
        return command;
    }

    private String readAll(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        }
    }

    /**
     * JSON → 결과 객체.
     *
     * <p>행을 읽을 때 <b>필드 목록으로 값을 꺼낸다.</b> 노드를 통째로 Map 으로 변환하면 값 타입이
     * 섞여 들어오는데, 이 계층의 계약은 "모든 값은 문자열"이다 — 타입 변환은 매핑 정의를 아는
     * 변환 엔진의 일이지 파서의 일이 아니다.
     */
    private ExcelParseResult toResult(String stdout, Path file) {
        try {
            JsonNode node = MAPPER.readTree(stdout);

            List<String> fields = new ArrayList<>();
            JsonNode fieldsNode = node.path("fields");
            for (int i = 0; i < fieldsNode.size(); i++) {
                fields.add(fieldsNode.get(i).asString(""));
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            JsonNode rowsNode = node.path("rows");
            for (int i = 0; i < rowsNode.size(); i++) {
                JsonNode rowNode = rowsNode.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                for (String field : fields) {
                    row.put(field, rowNode.path(field).asString(""));
                }
                rows.add(row);
            }

            return new ExcelParseResult(fields, rows, node.path("row_count").asInt(rows.size()));

        } catch (JacksonException e) {
            // 조용히 빈 결과를 돌려주면 "0건 성공"으로 보여 아무도 이상을 눈치채지 못한다.
            log.warn("엑셀 파싱 출력이 JSON 이 아니다 — {}", file, e);
            throw new BusinessException(ErrorCode.CONNECTOR_SOURCE_UNREACHABLE, fileName(file));
        }
    }

    private String fileName(Path file) {
        Path name = file.getFileName();
        return name == null ? file.toString() : name.toString();
    }
}
