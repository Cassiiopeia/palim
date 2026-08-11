package kr.suhsaechan.palim.common.config;

import java.math.BigDecimal;
import tools.jackson.core.type.TypeReference;

/**
 * 설정 읽기 창구.
 *
 * <p>설정을 쓰는 쪽이 {@link SystemConfigService} 구현체가 아니라 이 인터페이스에 의존하게 해서,
 * 단위 테스트가 DB 없이 기본값 맵만으로 돌아가게 한다. 스코어링 조립기가 이것만 받는 이유다.
 *
 * <h2>주의 — 빈 생성 시점에 읽지 않는다</h2>
 *
 * <p>설정은 {@link SystemConfigBootstrap} 이 채우는데 이는 <b>모든 빈이 만들어진 뒤</b>에
 * 실행된다. 따라서 생성자·{@code @PostConstruct} 에서 값을 읽으면 {@code CONFIG_NOT_FOUND} 로
 * 기동이 실패한다. 설정에 의존하는 초기화는 <b>첫 사용 시점으로 미룬다</b>.
 */
public interface ConfigReader {

    int getInt(String key);

    long getLong(String key);

    double getDouble(String key);

    BigDecimal getDecimal(String key);

    boolean getBoolean(String key);

    String getString(String key);

    <T> T getObject(String key, TypeReference<T> typeReference);
}
