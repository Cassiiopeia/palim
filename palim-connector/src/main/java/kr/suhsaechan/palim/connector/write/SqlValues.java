package kr.suhsaechan.palim.connector.write;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code JdbcClient} 파라미터 변환.
 *
 * <p>두 가지 함정을 여기 모아 둔다.
 *
 * <ul>
 *   <li><b>{@code Instant} 는 바인딩되지 않는다.</b> {@code timestamptz} 컬럼에는
 *       {@link OffsetDateTime} 을 넘겨야 한다 — 드라이버가 {@code Instant} 를 모른다</li>
 *   <li><b>{@code Map} 은 JSONB 로 바인딩되지 않는다.</b> JSON 문자열로 직렬화한 뒤 SQL 쪽에서
 *       {@code cast(... as jsonb)} 로 받는다</li>
 * </ul>
 *
 * <p>두 실수 모두 컴파일은 통과하고 실행 시점에야 드러난다. 한 곳에 모아 두지 않으면 적재
 * 코드마다 같은 함정을 다시 밟는다.
 */
public final class SqlValues {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SqlValues() {
    }

    /** 변환 엔진이 만든 값을 드라이버가 아는 타입으로 바꾼다. */
    public static Object toParameter(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
            case LocalDate date -> date;
            default -> value;
        };
    }

    /** JSONB 컬럼용 문자열. {@code null} 이나 빈 맵도 유효한 JSON 이어야 한다. */
    public static String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return "{}";
        }
        return MAPPER.writeValueAsString(value);
    }
}
