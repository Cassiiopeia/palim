package kr.suhsaechan.palim.connector.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 길이 제한 컬럼에 값을 넣기 전 정리.
 *
 * <p>PostgreSQL 의 {@code varchar(n)} 은 초과 값을 조용히 자르지 않고 <b>22001 에러로
 * 트랜잭션을 중단</b>시킨다. 실패 행을 기록하려다 실행 전체가 죽으면 부분 실패 허용이라는
 * 설계가 통째로 무너지므로, 저장 직전에 길이를 맞춘다.
 *
 * <p>용도에 따라 방법이 다르다. <b>표시용 문자열은 잘라도 되지만 식별자는 자르면 안 된다</b> —
 * 서로 다른 자연키의 앞부분이 같으면 UPSERT 가 엉뚱한 행을 덮어쓴다.
 */
public final class ColumnText {

    /** 축약 표시. 값이 잘렸음을 사람이 알 수 있어야 한다. */
    private static final String ELLIPSIS = "…";

    /** 해시 접미사 길이(16진수 문자 수). 64비트면 현실적인 충돌 확률이 0에 수렴한다. */
    private static final int HASH_LENGTH = 16;

    private static final char HASH_SEPARATOR = '~';

    private ColumnText() {
    }

    /**
     * 표시용 문자열 절단.
     *
     * <p>메시지·요약처럼 <b>잘려도 의미가 크게 손상되지 않는 값</b>에만 쓴다. 식별자에는
     * {@link #shortenKey(String, int)} 를 쓴다.
     */
    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - ELLIPSIS.length()) + ELLIPSIS;
    }

    /**
     * 식별자 축약.
     *
     * <p>단순 절단이 아니라 <b>앞부분 + 전체 해시</b>다. 자연키 필드가 많거나 값이 길면 앞부분이
     * 겹칠 수 있는데, 그때 절단하면 서로 다른 두 행이 같은 키가 되어 UPSERT 가 남의 행을
     * 덮어쓴다. 데이터가 조용히 사라지는 유형의 사고다.
     *
     * <p>앞부분을 남기는 이유는 화면·로그에서 사람이 어떤 행인지 알아볼 수 있어야 하기 때문이다.
     */
    public static String shortenKey(String key, int maxLength) {
        if (key == null || key.length() <= maxLength) {
            return key;
        }
        String hash = sha256Hex(key).substring(0, HASH_LENGTH);
        int prefixLength = maxLength - HASH_LENGTH - 1;
        return key.substring(0, prefixLength) + HASH_SEPARATOR + hash;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 이 제공한다. 여기 오면 런타임이 깨진 것이다.
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }
}
