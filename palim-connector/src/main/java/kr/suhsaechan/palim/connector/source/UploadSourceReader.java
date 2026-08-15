package kr.suhsaechan.palim.connector.source;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.excel.ExcelParseResult;
import kr.suhsaechan.palim.connector.excel.ExcelParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 업로드 파일(엑셀·CSV) 원천.
 *
 * <p>API 가 막혀도 업무가 멈추지 않는 경로다. 연동이 언젠가 반드시 끊기는데(인증 만료·점검·
 * 양식 변경) 그때 사람이 파일을 올려 계속 돌릴 수 있어야 한다.
 */
@Component
@RequiredArgsConstructor
public class UploadSourceReader implements SourceReader {

    /** 미리보기 행 수. 5행이면 컬럼의 성격(코드·수량·날짜)이 눈에 드러난다. */
    /** 매핑 화면에 보여줄 행 수. 몇 줄만 보여주면 무엇을 고를지 정할 수가 없다. */
    private static final int SAMPLE_LIMIT = 200;

    private final ExcelParser excelParser;

    @Override
    public SourceType type() {
        return SourceType.UPLOAD;
    }

    @Override
    public SourceSchema readSchema(SourceContext context) {
        ExcelParseResult result = excelParser.parse(context.file(), context.headerRow(),
                SAMPLE_LIMIT);
        return new SourceSchema(result.fields(), result.rows(), result.rowCount());
    }

    @Override
    public Stream<SourceRow> read(SourceContext context) {
        ExcelParseResult result = excelParser.parse(context.file(), context.headerRow(), 0);
        List<Map<String, Object>> rows = result.rows();

        // 행 번호는 1부터. 실패 행을 사람이 파일에서 찾을 수 있어야 한다.
        return IntStream.range(0, rows.size())
                .mapToObj(index -> new SourceRow(index + 1, rows.get(index)));
    }
}
