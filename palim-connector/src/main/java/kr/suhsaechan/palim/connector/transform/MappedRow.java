package kr.suhsaechan.palim.connector.transform;

import java.util.Map;

/**
 * 변환 결과.
 *
 * @param values     목표 필드 키 → 변환된 값
 * @param attributes 매핑되지 않은 원천 컬럼. <b>버리지 않는다</b> — 처음엔 필요 없어 보이던
 *                   컬럼이 나중에 필요해지는데, 과거 시점 데이터는 다시 받을 수 없다
 */
public record MappedRow(int rowNumber, Map<String, Object> values,
                        Map<String, Object> attributes) {
}
