package kr.suhsaechan.palim.web.connector;

/**
 * 테스트 적재 결과 한 줄.
 *
 * <p>변환 결과를 그대로 보여주므로 "이 값이 이렇게 들어갑니다"를 확정 전에 확인할 수 있다.
 * {@code naturalKey} 는 LIVE 였다면 무엇을 기준으로 UPSERT 됐을지를 뜻한다.
 */
public record StagingRow(int rowNumber, String naturalKey, String payload) {
}
