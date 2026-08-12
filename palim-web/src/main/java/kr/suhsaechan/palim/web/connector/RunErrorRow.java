package kr.suhsaechan.palim.web.connector;

/**
 * 실패한 행.
 *
 * <p>원본을 그대로 보여준다. 행 번호와 에러 코드만 있으면 사람이 원본 파일을 다시 열어
 * 대조해야 하는데, 원천이 API 라면 그 시점 데이터를 다시 볼 방법이 없다.
 */
public record RunErrorRow(int rowNumber, String errorCode, String message, String sourceRow) {
}
