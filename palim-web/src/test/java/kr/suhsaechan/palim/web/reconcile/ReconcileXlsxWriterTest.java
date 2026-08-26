package kr.suhsaechan.palim.web.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 대조 결과가 <b>진짜 엑셀 파일</b>로 나오는가.
 *
 * <p>스크립트를 <b>실제로 실행</b>한다. 모킹하면 규약 위반(stdout 에 사람용 메시지가 섞이는 등)을
 * 잡지 못하는데, 그것이 이 계층에서 가장 자주 깨지는 지점이다.
 *
 * <p>엑셀 라이브러리는 배포 이미지와 CI 에 모두 들어 있다. <b>건너뛰게 만들지 않는다</b> —
 * 조용히 건너뛰는 시험은 없는 시험이고, 그러면 「내려받았는데 안 열린다」 를 사람이 먼저 찾는다.
 */
class ReconcileXlsxWriterTest {

    private final ReconcileXlsxWriter writer =
            new ReconcileXlsxWriter("python3", scriptDirectory(), 30);

    /**
     * 스크립트 위치는 저장소 루트 기준이다.
     *
     * <p>Gradle 은 각 모듈 디렉터리를 작업 디렉터리로 삼으므로 {@code scripts} 상대경로가
     * {@code palim-web/scripts} 를 가리킨다. 루트를 찾아 절대경로로 넘긴다.
     */
    private static String scriptDirectory() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("scripts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("scripts 디렉터리를 찾지 못했습니다");
        }
        return current.resolve("scripts").toString();
    }

    @Test
    @DisplayName("엑셀 파일이 만들어진다")
    void writesWorkbook() {
        byte[] excel = writer.write(
                List.of(new ReconcileXlsxWriter.Line("대조", "합성 대조")),
                List.of(new ReconcileXlsxWriter.Sheet("지금 손댈 것",
                        List.of("묶음 코드", "품목", "차이"), 2,
                        List.of(Map.of("묶음 코드", "00094", "품목", "합성 품목", "차이", "+27")))));

        assertThat(excel).isNotEmpty();
        // xlsx 는 zip 이다. 앞 두 바이트가 PK 여야 엑셀이 연다.
        assertThat(new String(excel, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
    }

    /**
     * <b>품목코드가 숫자로 바뀌지 않는다.</b>
     *
     * <p>엑셀은 숫자처럼 「생긴」 값을 숫자로 해석한다. 그러면 {@code 00094} 가 {@code 94} 로
     * 바뀌는데, 이 제품의 핵심이 그 코드를 견주는 것이므로 <b>내려받은 파일로는 아무것도 못
     * 맞추게 된다.</b> 같은 함정을 화면에서 이미 한 번 겪었다.
     */
    @Test
    @DisplayName("품목코드가 글자 그대로 남는다")
    void keepsLeadingZeros() throws Exception {
        byte[] excel = writer.write(
                List.of(new ReconcileXlsxWriter.Line("대조", "합성 대조")),
                List.of(new ReconcileXlsxWriter.Sheet("지금 손댈 것",
                        List.of("묶음 코드", "품목"), 2,
                        List.of(Map.of("묶음 코드", "00094", "품목", "합성 품목")))));

        // 값이 «글자» 로 저장됐는지 본다. 숫자로 해석됐다면 <v>94</v> 가 되어 앞의 0 이
        // 사라지고, 그 파일로는 아무것도 못 맞춘다.
        String sheet = entryOf(excel, "xl/worksheets/sheet2.xml");
        assertThat(sheet)
                .as("앞의 0 이 살아 있어야 한다")
                .contains("<t>00094</t>");
        assertThat(sheet)
                .as("글자 타입이어야 엑셀이 다시 숫자로 해석하지 않는다")
                .contains("t=\"inlineStr\"");
        assertThat(sheet)
                .as("숫자로 들어갔다면 이 모양이 된다")
                .doesNotContain("<v>94</v>");
    }

    @Test
    @DisplayName("갈래마다 장이 하나씩 생기고 「무엇을 견줬나」 가 함께 담긴다")
    void writesEverySheet() throws Exception {
        byte[] excel = writer.write(
                List.of(new ReconcileXlsxWriter.Line("견준 시점", "2026-08-25 07:00")),
                List.of(
                        new ReconcileXlsxWriter.Sheet("지금 손댈 것", List.of("품목"), 1, List.of()),
                        new ReconcileXlsxWriter.Sheet("지켜볼 것", List.of("품목"), 1, List.of()),
                        new ReconcileXlsxWriter.Sheet("짝이 없는 것", List.of("품목"), 1, List.of())));

        assertThat(entryOf(excel, "xl/workbook.xml"))
                .as("파일이 스스로 무엇인지 말해야 며칠 뒤에 열어도 화면과 짝지을 수 있다")
                .contains("이 대조는")
                .contains("지금 손댈 것")
                .contains("지켜볼 것")
                .contains("짝이 없는 것");
    }

    /** 줄이 하나도 없어도 파일은 나와야 한다 — 「차이 없음」 도 결과다. */
    @Test
    @DisplayName("결과가 비어도 파일은 나온다")
    void writesEvenWhenEmpty() {
        byte[] excel = writer.write(
                List.of(new ReconcileXlsxWriter.Line("대조", "합성 대조")),
                List.of(new ReconcileXlsxWriter.Sheet("지금 손댈 것",
                        List.of("묶음 코드", "품목"), 2, List.of())));

        assertThat(excel).isNotEmpty();
    }

    /** xlsx 안의 한 조각을 꺼낸다. */
    private String entryOf(byte[] excel, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(excel))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }
}
