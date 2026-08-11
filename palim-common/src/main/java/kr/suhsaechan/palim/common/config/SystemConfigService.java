package kr.suhsaechan.palim.common.config;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 런타임 설정 읽기·쓰기.
 *
 * <p><b>캐시가 이 서비스의 존재 이유다.</b> 스코어링은 채널 한 건을 채점할 때마다 수십 개의
 * 임계값을 읽는다. 매번 DB 를 때리면 배치가 설정 조회로 시간을 다 쓴다. 그래서 전체 설정을
 * 메모리에 올려두고, 변경 시점에만 갱신한다.
 *
 * <p>인스턴스가 하나인 모놀리스라 이 방식이 성립한다. 다중 인스턴스로 가면 변경 전파(발행·구독
 * 또는 짧은 TTL)가 필요해지며, 그때 바꿀 지점은 {@link #evict}/{@link #reload} 두 곳뿐이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigService implements ConfigReader {

    private final SystemConfigRepository configRepository;
    private final SystemConfigHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    /** key → JSON 문자열. 부팅 시 채우고 변경 시 갱신한다. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    // ==================================================================
    // 읽기
    // ==================================================================

    @Override
    public String getString(String key) {
        return readNode(key).asString();
    }

    @Override
    public int getInt(String key) {
        return readNode(key).asInt();
    }

    @Override
    public long getLong(String key) {
        return readNode(key).asLong();
    }

    @Override
    public double getDouble(String key) {
        return readNode(key).asDouble();
    }

    @Override
    public BigDecimal getDecimal(String key) {
        return readNode(key).decimalValue();
    }

    @Override
    public boolean getBoolean(String key) {
        return readNode(key).asBoolean();
    }

    /** 객체·배열을 원하는 타입으로 역직렬화한다(보간 곡선·카테고리 목록 등). */
    public <T> T getObject(String key, Class<T> type) {
        try {
            return objectMapper.readValue(rawValue(key), type);
        } catch (JacksonException e) {
            log.error("설정 역직렬화 실패 — key={}, type={}", key, type.getSimpleName(), e);
            throw new BusinessException(ErrorCode.CONFIG_VALUE_INVALID, key);
        }
    }

    /** 제네릭 타입(List·Map 등)이 필요할 때. */
    @Override
    public <T> T getObject(String key, tools.jackson.core.type.TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(rawValue(key), typeReference);
        } catch (JacksonException e) {
            log.error("설정 역직렬화 실패 — key={}", key, e);
            throw new BusinessException(ErrorCode.CONFIG_VALUE_INVALID, key);
        }
    }

    // ==================================================================
    // 쓰기
    // ==================================================================

    /**
     * 값 변경. 화면·API 가 호출하는 유일한 쓰기 경로다.
     *
     * <p>검증 → 이력 기록 → 저장 → 캐시 갱신 순서다. 캐시를 마지막에 갱신하는 이유는 검증이나
     * 저장이 실패했을 때 메모리만 바뀐 상태로 남지 않게 하기 위해서다.
     */
    @Transactional
    public void update(String key, String jsonValue, String updatedBy) {
        SystemConfig config = configRepository.findByConfigKey(key)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFIG_NOT_FOUND, key));

        if (!config.isEditable()) {
            throw new BusinessException(ErrorCode.CONFIG_NOT_EDITABLE, key);
        }

        String normalized = validate(config, jsonValue);
        String previous = config.getConfigValue();

        historyRepository.save(SystemConfigHistory.of(key, previous, normalized, updatedBy,
                Instant.now()));
        config.changeValue(normalized, updatedBy);
        configRepository.save(config);
        cache.put(key, normalized);

        log.info("설정 변경 — key={}, {} -> {}, 변경자={}", key, previous, normalized, updatedBy);
    }

    /** 여러 값을 한 번에 변경한다 — 가중치 세트처럼 함께 바뀌어야 의미가 있는 경우. */
    @Transactional
    public void updateAll(Map<String, String> values, String updatedBy) {
        values.forEach((key, value) -> update(key, value, updatedBy));
    }

    // ==================================================================
    // 조회 (화면용)
    // ==================================================================

    @Transactional(readOnly = true)
    public List<SystemConfig> findByCategory(String category) {
        return configRepository.findByCategoryOrderBySortOrderAsc(category);
    }

    @Transactional(readOnly = true)
    public List<SystemConfig> findAllEditable() {
        return configRepository.findByEditableTrueOrderByCategoryAscSortOrderAsc();
    }

    @Transactional(readOnly = true)
    public List<SystemConfigHistory> findHistory(String key, int limit) {
        return historyRepository.findByConfigKeyOrderByChangedAtDesc(key, Limit.of(limit));
    }

    // ==================================================================
    // 캐시
    // ==================================================================

    /** 전체 캐시 재적재. 부트스트랩이 정의를 채운 직후에 호출한다. */
    @Transactional(readOnly = true)
    public void reload() {
        cache.clear();
        configRepository.findAll()
                .forEach(config -> cache.put(config.getConfigKey(), config.getConfigValue()));
        log.info("설정 캐시 적재 — {}건", cache.size());
    }

    public void evict(String key) {
        cache.remove(key);
    }

    // ==================================================================
    // 내부
    // ==================================================================

    private String rawValue(String key) {
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        // 캐시 미스는 부팅 순서 문제이거나 정의 누락이다. DB 를 한 번 더 보고 캐시에 얹는다.
        String value = configRepository.findByConfigKey(key)
                .map(SystemConfig::getConfigValue)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFIG_NOT_FOUND, key));
        cache.put(key, value);
        return value;
    }

    private JsonNode readNode(String key) {
        try {
            return objectMapper.readTree(rawValue(key));
        } catch (JacksonException e) {
            log.error("설정 파싱 실패 — key={}", key, e);
            throw new BusinessException(ErrorCode.CONFIG_VALUE_INVALID, key);
        }
    }

    /**
     * 값 검증.
     *
     * <p>설정 하나가 잘못되면 시스템 전체 동작이 바뀌므로 저장 전에 막는다. 특히 배점에 음수나
     * 비정상적으로 큰 값이 들어가면 점수 체계가 통째로 무너진다.
     */
    private String validate(SystemConfig config, String jsonValue) {
        JsonNode node;
        try {
            node = objectMapper.readTree(jsonValue);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.CONFIG_VALUE_INVALID, config.getConfigKey());
        }

        switch (config.getValueType()) {
            case INTEGER, DECIMAL -> {
                if (!node.isNumber()) {
                    throw new BusinessException(ErrorCode.CONFIG_VALUE_INVALID, config.getConfigKey());
                }
                BigDecimal value = node.decimalValue();
                if (config.getMinValue() != null && value.compareTo(config.getMinValue()) < 0) {
                    throw new BusinessException(ErrorCode.CONFIG_VALUE_OUT_OF_RANGE,
                            config.getConfigKey(), config.getMinValue(), config.getMaxValue());
                }
                if (config.getMaxValue() != null && value.compareTo(config.getMaxValue()) > 0) {
                    throw new BusinessException(ErrorCode.CONFIG_VALUE_OUT_OF_RANGE,
                            config.getConfigKey(), config.getMinValue(), config.getMaxValue());
                }
            }
            case BOOLEAN -> {
                if (!node.isBoolean()) {
                    throw new BusinessException(ErrorCode.CONFIG_VALUE_INVALID, config.getConfigKey());
                }
            }
            case STRING -> {
                if (!node.isString()) {
                    throw new BusinessException(ErrorCode.CONFIG_VALUE_INVALID, config.getConfigKey());
                }
            }
            case JSON -> {
                if (!node.isObject() && !node.isArray()) {
                    throw new BusinessException(ErrorCode.CONFIG_VALUE_INVALID, config.getConfigKey());
                }
            }
        }
        // 파싱된 노드를 다시 직렬화해 공백·표기를 정규화한다.
        return node.toString();
    }
}
