package kr.suhsaechan.palim.reconcile.filter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 걸 수 있는 칸의 <b>전부</b>.
 *
 * <p>여기 없는 이름은 SQL 이 되지 않는다. 사용자가 칸 이름을 자유 입력하지 않게 하는 이유는
 * 셋이다 — 오타가 도는 순간까지 안 잡히고, 원천 구성이 바뀌어 칸이 사라지면 조건도 조용히
 * 사라지며, 식별자 자리에 임의 문자열이 들어갈 길을 애초에 없애기 위해서다.
 *
 * <p><b>표준에 없는 원천 칸도 전부 걸 수 있다.</b> 매핑되지 않은 원천 컬럼을 {@code attributes}
 * jsonb 에 통째로 살려 두기 때문이다. 그래서 원천 계정이 바뀌어 칸 구성이 달라져도 화면이
 * 그대로 동작한다 — 코드에 칸 이름을 박지 않는 이유가 이것이다.
 */
public final class FieldCatalog {

    /** {@code attributes} 안의 칸을 가리키는 접두어. */
    public static final String ATTRIBUTE_PREFIX = "attributes.";

    private static final Map<String, FilterableField> STANDARD = new LinkedHashMap<>();

    static {
        text("warehouse_code", "창고");
        text("warehouse_name", "창고명");
        text("lot_code", "로트");
        text("location_code", "로케이션");
        text("zone_code", "구역");
        text("quality_status", "품질상태");
        text("unit", "단위");
        text("base_unit", "기준단위");
        text("serial_no", "일련번호");
        text("raw_item_name", "원본품명");
        text("normalized_name", "다듬은품명");
        text("item_ref", "품목코드");
        text("currency", "통화");
        date("expiry_date", "유통기한");
        date("manufacture_date", "제조일");
        number("quantity", "원본수량");
        number("base_quantity", "기준수량");
        number("available_quantity", "가용수량");
        number("reserved_quantity", "할당수량");
        number("defective_quantity", "불량수량");
        number("incoming_quantity", "입고예정");
        number("outgoing_quantity", "출고예정");
        number("unit_cost", "단가");
        number("amount", "금액");
    }

    private FieldCatalog() {
    }

    private static void text(String key, String label) {
        put(key, label, FieldType.TEXT);
    }

    private static void number(String key, String label) {
        put(key, label, FieldType.NUMBER);
    }

    private static void date(String key, String label) {
        put(key, label, FieldType.DATE);
    }

    private static void put(String key, String label, FieldType type) {
        STANDARD.put(key, new FilterableField(key, label, type, key, false));
    }

    /**
     * 표준 칸 전부. 화면이 드롭다운을 그리는 데 쓴다.
     *
     * <p><b>화면 이름에 공백을 두지 않는다.</b> 식은 낱말 단위로 읽으므로 「원본 품명」 처럼
     * 띄어 쓰면 두 낱말이 되어 되읽을 수 없다 — 「식으로 보기」 로 나온 글을 그대로 저장할 수
     * 없게 된다.
     */
    public static List<FilterableField> standard() {
        return List.copyOf(STANDARD.values());
    }

    /**
     * 이름으로 칸을 찾는다. <b>못 찾으면 비어 있다</b> — 부르는 쪽이 거부해야 한다.
     *
     * <p>{@code attributes.«키»} 는 목록에 없어도 만들어 준다. 원천마다 키가 다르고 그 목록은
     * 담긴 자료에서만 알 수 있기 때문이다. 대신 <b>따옴표·역슬래시가 섞인 키는 거부한다</b> —
     * 그 값은 표현식 문자열에 그대로 들어가는 유일한 자리다.
     */
    public static Optional<FilterableField> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        FilterableField standard = STANDARD.get(key);
        if (standard != null) {
            return Optional.of(standard);
        }
        if (!key.startsWith(ATTRIBUTE_PREFIX)) {
            return Optional.empty();
        }
        String attribute = key.substring(ATTRIBUTE_PREFIX.length());
        if (attribute.isBlank()
                || attribute.indexOf('\'') >= 0
                || attribute.indexOf('"') >= 0
                || attribute.indexOf('\\') >= 0) {
            return Optional.empty();
        }
        // 원천 고유 칸은 언제나 글로 읽는다. jsonb 에서 ->> 로 꺼내면 문자열이기 때문이고,
        // 숫자로 다루고 싶으면 매핑에서 표준 칸으로 옮기는 것이 옳은 자리다.
        return Optional.of(new FilterableField(key, attribute, FieldType.TEXT,
                "attributes->>'%s'".formatted(attribute), true));
    }

    /** 담긴 자료에서 찾은 원천 고유 키들을 걸 수 있는 칸으로 바꾼다. */
    public static List<FilterableField> attributeFields(List<String> keys) {
        return keys.stream()
                .map(key -> find(ATTRIBUTE_PREFIX + key))
                .flatMap(Optional::stream)
                .toList();
    }
}
