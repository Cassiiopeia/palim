package kr.suhsaechan.palim.connector.source;

import java.util.Map;

/**
 * 원천의 한 행.
 *
 * <p>{@code rowNumber} 는 1부터다. 실패 행을 사람이 원본 파일에서 찾아갈 수 있는 유일한
 * 좌표이므로, 걸러진 빈 행 때문에 번호가 밀리지 않도록 <b>결과 기준</b>으로 매긴다.
 */
public record SourceRow(int rowNumber, Map<String, Object> values) {
}
