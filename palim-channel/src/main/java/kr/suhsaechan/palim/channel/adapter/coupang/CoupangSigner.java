package kr.suhsaechan.palim.channel.adapter.coupang;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;

/**
 * 쿠팡 Open API 요청 서명 (HMAC-SHA256).
 *
 * <p>쿠팡은 요청마다 서명을 요구한다. 서명이 틀리면 401 이 돌아오고, 그것이 반복되면
 * <b>계정이 영구 차단</b>될 수 있으므로 규격을 정확히 지켜야 한다.
 *
 * <h2>서명 대상 문자열</h2>
 *
 * <pre>{@code {signedDate}{method}{path}{query}}</pre>
 *
 * <p>구분자가 없다. 그대로 이어 붙인다.
 *
 * <h2>signedDate 는 UTC 다</h2>
 *
 * <p>형식은 {@code yyMMdd'T'HHmmss'Z'} 이며 <b>반드시 UTC 기준</b>이다. 서버 로컬 시각으로
 * 만들면 KST 환경에서 9시간 어긋나 서명이 통째로 무효가 된다. 이 값은 요청 헤더에도 같은
 * 문자열로 들어가므로 <b>서명과 헤더가 같은 값을 써야 한다.</b>
 *
 * <h2>query 는 앞의 {@code ?} 를 제외한다</h2>
 *
 * <p>query 가 없으면 빈 문자열이다. {@code null} 을 이어 붙이면 문자열 "null" 이 서명에
 * 들어가 실패한다.
 */
public final class CoupangSigner {

    private static final String ALGORITHM = "HmacSHA256";

    /** 쿠팡 규격. UTC 기준이며 초 단위까지만 쓴다. */
    private static final DateTimeFormatter SIGNED_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private CoupangSigner() {
    }

    /** 요청 시각을 쿠팡 규격 문자열로 만든다. 서명과 헤더에 같은 값을 쓴다. */
    public static String signedDate(Instant requestedAt) {
        return SIGNED_DATE_FORMAT.format(requestedAt);
    }

    /**
     * {@code Authorization} 헤더 값을 만든다.
     *
     * @param method     HTTP 메서드 (대문자)
     * @param path       경로. 쿼리스트링을 포함하지 않는다
     * @param query      쿼리스트링. 앞의 {@code ?} 를 제외하며 없으면 빈 문자열
     * @param signedDate {@link #signedDate(Instant)} 로 만든 값
     */
    public static String authorizationHeader(String method, String path, String query,
                                             String signedDate, String accessKey, String secretKey) {
        String signature = sign(method, path, query, signedDate, secretKey);

        return "CEA algorithm=HmacSHA256, access-key=%s, signed-date=%s, signature=%s"
                .formatted(accessKey, signedDate, signature);
    }

    /** 서명 문자열(hex)을 만든다. 테스트가 기대값을 직접 검증할 수 있게 공개한다. */
    public static String sign(String method, String path, String query,
                              String signedDate, String secretKey) {
        String message = signedDate
                + method.toUpperCase(Locale.ROOT)
                + path
                + (query != null ? query : "");

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new BusinessException(ErrorCode.CHANNEL_API_FAILED, exception, "쿠팡");
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }
}
