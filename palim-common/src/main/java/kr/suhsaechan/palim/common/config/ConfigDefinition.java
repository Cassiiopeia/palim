package kr.suhsaechan.palim.common.config;

import java.math.BigDecimal;

/**
 * 설정 항목의 선언.
 *
 * <p>이것이 이 구조의 확장 지점이다. 각 모듈이 {@link ConfigDefinitionProvider} 로 정의를
 * 내놓으면 부팅 시 없는 키가 자동 등록되고 <b>설정 화면에 자동으로 나타난다</b> — 새 설정을
 * 추가할 때 화면 코드도, 마이그레이션도 건드리지 않는다.
 *
 * @param key          점 표기 계층 키
 * @param defaultValue JSON 리터럴 문자열. 스칼라도 JSON 이다("14.0", "\"text\"", "true")
 * @param minValue     숫자형의 하한. 아니면 null
 * @param maxValue     숫자형의 상한. 아니면 null
 * @param editable     false 면 화면에서 감춘다(내부 상태값)
 * @param sortOrder    같은 카테고리 안의 표시 순서
 */
public record ConfigDefinition(
        String key,
        String defaultValue,
        ConfigValueType valueType,
        String category,
        String displayName,
        String description,
        boolean editable,
        BigDecimal minValue,
        BigDecimal maxValue,
        int sortOrder) {

    /** 슬라이더로 조정하는 소수값(배점·임계값). 대부분의 스코어링 설정이 여기 속한다. */
    public static ConfigDefinition decimal(String key, double defaultValue, double min, double max,
                                           String category, String displayName, String description,
                                           int sortOrder) {
        return new ConfigDefinition(key, String.valueOf(defaultValue), ConfigValueType.DECIMAL,
                category, displayName, description, true,
                BigDecimal.valueOf(min), BigDecimal.valueOf(max), sortOrder);
    }

    public static ConfigDefinition integer(String key, int defaultValue, int min, int max,
                                           String category, String displayName, String description,
                                           int sortOrder) {
        return new ConfigDefinition(key, String.valueOf(defaultValue), ConfigValueType.INTEGER,
                category, displayName, description, true,
                BigDecimal.valueOf(min), BigDecimal.valueOf(max), sortOrder);
    }

    public static ConfigDefinition bool(String key, boolean defaultValue, String category,
                                        String displayName, String description, int sortOrder) {
        return new ConfigDefinition(key, String.valueOf(defaultValue), ConfigValueType.BOOLEAN,
                category, displayName, description, true, null, null, sortOrder);
    }

    /** 값에 JSON 문자열 리터럴이 되도록 따옴표를 씌운다. */
    public static ConfigDefinition text(String key, String defaultValue, String category,
                                        String displayName, String description, int sortOrder) {
        return new ConfigDefinition(key, "\"" + defaultValue + "\"", ConfigValueType.STRING,
                category, displayName, description, true, null, null, sortOrder);
    }

    /** 객체·배열. defaultValue 는 유효한 JSON 이어야 한다(보간 곡선·카테고리 목록 등). */
    public static ConfigDefinition json(String key, String defaultJson, String category,
                                        String displayName, String description, int sortOrder) {
        return new ConfigDefinition(key, defaultJson, ConfigValueType.JSON, category, displayName,
                description, true, null, null, sortOrder);
    }

    /** 화면에 노출하지 않는 내부 상태값(배치 커서 등). */
    public static ConfigDefinition internal(String key, String defaultJson,
                                            ConfigValueType valueType, String category,
                                            String displayName) {
        return new ConfigDefinition(key, defaultJson, valueType, category, displayName, null,
                false, null, null, 999);
    }
}
