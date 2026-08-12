package kr.suhsaechan.palim.connector.write;

/**
 * 적재 결과.
 *
 * <p>{@code inserted} 와 {@code updated} 를 나누지 않는다. UPSERT 는 어느 쪽이 일어났는지
 * 표준 SQL 로 구분하기 어렵고, 사용자에게도 "몇 건 반영됐나"만 의미가 있다.
 */
public record WriteResult(int written) {

    public static WriteResult of(int written) {
        return new WriteResult(written);
    }
}
