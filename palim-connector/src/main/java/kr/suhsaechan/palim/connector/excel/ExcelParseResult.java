package kr.suhsaechan.palim.connector.excel;

import java.util.List;
import java.util.Map;

/**
 * py 스크립트 출력.
 *
 * @param fields   이름이 있는 열 목록. 매핑 편집기의 왼쪽에 그려진다
 * @param rows     각 행. 값은 전부 문자열이며 타입 변환은 변환 엔진이 한다
 * @param rowCount <b>제한과 무관한 전체 건수.</b> 미리보기에서 "N건 중 5건"을 표시해야 한다
 */
public record ExcelParseResult(List<String> fields, List<Map<String, Object>> rows, int rowCount) {
}
