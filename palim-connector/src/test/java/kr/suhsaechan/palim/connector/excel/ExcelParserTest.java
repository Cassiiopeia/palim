package kr.suhsaechan.palim.connector.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 엑셀 파서 어댑터.
 *
 * <p>py 스크립트를 <b>실제로 실행</b>한다. 모킹하면 규약 위반(stdout 에 사람용 메시지가 섞이는
 * 등)을 잡지 못하는데, 그것이 이 계층에서 가장 자주 깨지는 지점이다.
 *
 * <p>CSV 만 쓴다. 스크립트가 CSV 를 표준 라이브러리로 처리하므로 openpyxl 이 없는 환경에서도
 * 이 테스트가 돈다 — 개발자가 파이썬 패키지를 깔아야 테스트가 통과하는 구조는 곧 아무도
 * 테스트를 안 돌리는 구조가 된다.
 */
class ExcelParserTest {

    @TempDir
    Path tempDir;

    private final ExcelParser parser = new ExcelParser(scriptProperties());

    @Test
    @DisplayName("CSV 헤더와 행을 읽는다")
    void CSV_를_읽는다() throws IOException {
        Path csv = write("""
                품목코드,품목명,재고수량
                A-001,샘플품목 100g (27.01.01),120
                A-002,샘플품목 200g (27.02.01),0
                """);

        ExcelParseResult result = parser.parse(csv, 1, 0);

        assertThat(result.fields()).containsExactly("품목코드", "품목명", "재고수량");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().getFirst())
                .containsEntry("품목명", "샘플품목 100g (27.01.01)")
                .containsEntry("재고수량", "120");
        assertThat(result.rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("미리보기 제한을 걸어도 전체 건수는 그대로 알려준다")
    void 미리보기는_전체_건수를_보존한다() throws IOException {
        Path csv = write("""
                코드,이름
                A,가
                B,나
                C,다
                """);

        ExcelParseResult result = parser.parse(csv, 1, 1);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rowCount())
                .as("화면이 'N건 중 1건 미리보기'를 표시해야 한다").isEqualTo(3);
    }

    @Test
    @DisplayName("헤더 위의 제목 행을 건너뛴다")
    void 헤더_행을_지정한다() throws IOException {
        Path csv = write("""
                재고현황 조회 결과
                코드,수량
                A-001,10
                """);

        ExcelParseResult result = parser.parse(csv, 2, 0);

        assertThat(result.fields()).containsExactly("코드", "수량");
        assertThat(result.rows()).hasSize(1);
    }

    @Test
    @DisplayName("파일 끝 빈 행은 버린다 — 넣으면 필수항목 없음으로 무더기 실패한다")
    void 빈_행을_버린다() throws IOException {
        Path csv = write("코드,수량\nA-001,10\n,\n,\n");

        ExcelParseResult result = parser.parse(csv, 1, 0);

        assertThat(result.rows()).hasSize(1);
    }

    @Test
    @DisplayName("이름 없는 열은 결과에서 제외한다")
    void 빈_헤더_열을_제외한다() throws IOException {
        Path csv = write("코드,,수량\nA-001,서식용,10\n");

        ExcelParseResult result = parser.parse(csv, 1, 0);

        assertThat(result.fields()).containsExactly("코드", "수량");
        assertThat(result.rows().getFirst()).doesNotContainKey("");
    }

    @Test
    @DisplayName("없는 파일은 원천 접근 실패로 처리한다")
    void 없는_파일은_실패한다() {
        Path missing = tempDir.resolve("does-not-exist.csv");

        assertThatThrownBy(() -> parser.parse(missing, 1, 0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONNECTOR_SOURCE_UNREACHABLE);
    }

    private Path write(String content) throws IOException {
        Path csv = tempDir.resolve("sample.csv");
        Files.writeString(csv, content, StandardCharsets.UTF_8);
        return csv;
    }

    /**
     * 스크립트 위치는 저장소 루트 기준이다.
     *
     * <p>Gradle 은 각 모듈 디렉터리를 작업 디렉터리로 삼으므로 {@code scripts} 상대경로가
     * {@code palim-connector/scripts} 를 가리킨다. 루트를 찾아 절대경로로 넘긴다.
     */
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
