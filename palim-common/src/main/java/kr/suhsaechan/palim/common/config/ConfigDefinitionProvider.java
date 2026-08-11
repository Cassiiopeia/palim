package kr.suhsaechan.palim.common.config;

import java.util.List;

/**
 * 모듈별 설정 정의 공급자.
 *
 * <p>구현체를 스프링 빈으로 등록하면 {@link SystemConfigBootstrap} 이 부팅 때 모두 모아
 * 없는 키를 채워 넣는다. 모듈은 자기 설정만 알면 되고, 설정 화면은 모듈을 알 필요가 없다.
 */
public interface ConfigDefinitionProvider {

    /** 이 모듈이 관리하는 설정 정의. */
    List<ConfigDefinition> definitions();
}
