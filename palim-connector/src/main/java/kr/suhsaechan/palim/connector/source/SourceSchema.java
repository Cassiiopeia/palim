package kr.suhsaechan.palim.connector.source;

import java.util.List;
import java.util.Map;

/**
 * 원천의 필드 구조와 샘플.
 *
 * <p>매핑 편집기의 왼쪽에 그려지고, 확정 시 {@code connector_mapping.source_schema} 에
 * 저장되어 이후 실행마다 드리프트 대조의 기준이 된다.
 *
 * @param totalCount 샘플이 아니라 <b>전체 건수</b>. 화면이 "N건 중 5건 미리보기"를 표시한다
 */
public record SourceSchema(List<String> fields, List<Map<String, Object>> sampleRows,
                           int totalCount) {
}
