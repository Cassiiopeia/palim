package kr.suhsaechan.palim.common.config;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DB 없이 쓰는 {@link ConfigReader} — 단위 테스트용.
 *
 * <p>기본값의 원본은 각 모듈의 {@link ConfigDefinitionProvider} 하나뿐이다. 테스트가 별도
 * 기본값 파일을 들고 있으면 운영값과 조용히 어긋나므로, 여기서도 같은 정의를 읽는다.
 */
public final class InMemoryConfigReader implements ConfigReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> values = new HashMap<>();

    private InMemoryConfigReader(List<ConfigDefinition> definitions) {
        definitions.forEach(definition -> values.put(definition.key(), definition.defaultValue()));
    }

    /** 정의의 기본값 그대로 읽는 리더. */
    public static InMemoryConfigReader ofDefaults(List<ConfigDefinition> definitions) {
        return new InMemoryConfigReader(definitions);
    }

    /** 특정 키만 바꿔 본다 — 가중치를 바꾸면 순위가 어떻게 달라지는지 검증할 때 쓴다. */
    public InMemoryConfigReader with(String key, String jsonValue) {
        values.put(key, jsonValue);
        return this;
    }

    @Override
    public int getInt(String key) {
        return node(key).asInt();
    }

    @Override
    public long getLong(String key) {
        return node(key).asLong();
    }

    @Override
    public double getDouble(String key) {
        return node(key).asDouble();
    }

    @Override
    public BigDecimal getDecimal(String key) {
        return node(key).decimalValue();
    }

    @Override
    public boolean getBoolean(String key) {
        return node(key).asBoolean();
    }

    @Override
    public String getString(String key) {
        return node(key).asString();
    }

    @Override
    public <T> T getObject(String key, TypeReference<T> typeReference) {
        return MAPPER.readValue(raw(key), typeReference);
    }

    private JsonNode node(String key) {
        return MAPPER.readTree(raw(key));
    }

    private String raw(String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("정의되지 않은 설정 키: " + key);
        }
        return value;
    }
}
