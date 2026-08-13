package kr.suhsaechan.palim.connector.model;

import java.util.List;

/**
 * 표준 모델 필드 하나의 정의.
 *
 * <p>{@code aliases} 는 <b>이 항목에 흔히 붙어 오는 원천 칸 이름</b>이다. 원천마다 부르는 말이
 * 달라도 뜻은 같은 경우가 대부분이라(수량 = BAL_QTY = QTY = STOCK_QTY), 그 목록을 여기 적어
 * 두면 첫 화면이 이미 채워진 채 열린다.
 *
 * <p>별칭을 항목 정의와 같은 자리에 두는 이유는 그것이 <b>항목의 성질</b>이기 때문이다. 따로
 * 관리하면 필드를 추가할 때 한쪽만 적고 잊는다.
 *
 * @param key         저장 컬럼 이름
 * @param displayName 화면에 보이는 이름
 * @param dataType    자료형
 * @param required    없으면 적재가 실패하는가
 * @param aliases     흔히 이 이름으로 온다. 없으면 빈 목록
 */
public record FieldDefinition(String key, String displayName, FieldDataType dataType,
                              boolean required, List<String> aliases) {

    public FieldDefinition {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public static FieldDefinition required(String key, String displayName, FieldDataType type) {
        return new FieldDefinition(key, displayName, type, true, List.of());
    }

    public static FieldDefinition required(String key, String displayName, FieldDataType type,
                                           String... aliases) {
        return new FieldDefinition(key, displayName, type, true, List.of(aliases));
    }

    public static FieldDefinition optional(String key, String displayName, FieldDataType type) {
        return new FieldDefinition(key, displayName, type, false, List.of());
    }

    public static FieldDefinition optional(String key, String displayName, FieldDataType type,
                                           String... aliases) {
        return new FieldDefinition(key, displayName, type, false, List.of(aliases));
    }
}
