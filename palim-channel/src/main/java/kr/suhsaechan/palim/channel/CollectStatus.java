package kr.suhsaechan.palim.channel;

/**
 * 채널 수집 결과.
 *
 * <p>실패가 연속되면 텔레그램으로 경고한다. 고정 IP 미등록이나 인증 만료로 수집이 중단되면
 * 주문이 없는 것처럼 보여 문제 인지가 늦어지기 때문이다(A-10).
 */
public enum CollectStatus {
    SUCCESS,
    FAILED
}
