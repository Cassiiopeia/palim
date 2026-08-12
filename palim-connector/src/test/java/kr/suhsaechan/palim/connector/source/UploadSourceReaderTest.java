package kr.suhsaechan.palim.connector.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.excel.ConnectorScriptProperties;
import kr.suhsaechan.palim.connector.excel.ExcelParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 업로드 원천 어댑터.
 *
 * <p>스키마 읽기와 전체 읽기가 <b>다른 동작</b>이라는 점을 확인한다. 하나로 합치면 매핑 화면을
 * 열 때마다 전체를 읽게 되고, 수만 행짜리 원천에서 화면이 멈춘다.
 */
class UploadSourceReaderTest {

    @TempDir
    Path tempDir;

    private final UploadSourceReader reader =
            new UploadSourceReader(new ExcelParser(scriptProperties()));

    @Test
    @DisplayName("업로드 유형을 담당한다")
    void 담당_유형() {
        assertThat(reader.type()).isEqualTo(SourceType.UPLOAD);
    }

    @Test
    @DisplayName("스키마 조회는 샘플만 읽되 전체 건수는 알려준다")
    void 스키마는_샘플만_읽는다() throws IOException {
        Path csv = write(csvWithRows(20));

        SourceSchema schema = reader.readSchema(SourceContext.ofUpload(null, csv, 1));

        assertThat(schema.fields()).containsExactly("코드", "수량");
        assertThat(schema.sampleRows())
                .as("매핑 화면이 전체를 읽으면 큰 파일에서 멈춘다").hasSize(5);
        assertThat(schema.totalCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("전체 읽기는 모든 행을 흘려보낸다")
    void 전체를_읽는다() throws IOException {
        Path csv = write(csvWithRows(20));

        List<SourceRow> rows = reader.read(SourceContext.ofUpload(null, csv, 1)).toList();

        assertThat(rows).hasSize(20);
    }

    @Test
    @DisplayName("행 번호는 1부터 매겨진다 — 실패 행을 찾아갈 좌표다")
    void 행_번호는_1부터() throws IOException {
        Path csv = write("코드,수량\nA-001,10\nA-002,20\n");

        List<SourceRow> rows = reader.read(SourceContext.ofUpload(null, csv, 1)).toList();

        assertThat(rows.get(0).rowNumber()).isEqualTo(1);
        assertThat(rows.get(1).rowNumber()).isEqualTo(2);
        assertThat(rows.get(1).values()).containsEntry("코드", "A-002");
    }

    private String csvWithRows(int count) {
        StringBuilder builder = new StringBuilder("코드,수량\n");
        for (int i = 1; i <= count; i++) {
            builder.append("A-%03d,%d%n".formatted(i, i * 10));
        }
        return builder.toString();
    }

    private Path write(String content) throws IOException {
        Path csv = tempDir.resolve("sample.csv");
        Files.writeString(csv, content, StandardCharsets.UTF_8);
        return csv;
    }

    private static ConnectorScriptProperties scriptProperties() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("scripts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("scripts 디렉터리를 찾지 못했습니다");
        }
        return new ConnectorScriptProperties("python3",
                current.resolve("scripts").toString(), 90);
    }
}
