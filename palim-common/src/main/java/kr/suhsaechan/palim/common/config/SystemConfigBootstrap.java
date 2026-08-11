package kr.suhsaechan.palim.common.config;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설정 초기화 — 정의에는 있는데 DB 에 없는 키를 채우고 캐시를 적재한다.
 *
 * <p>다른 부트스트랩보다 먼저 돌아야 한다({@code @Order(0)}). 뒤따르는 초기화가 설정을 읽을 수
 * 있어야 하기 때문이다.
 *
 * <p><b>기존 값은 절대 덮어쓰지 않는다.</b> 운영자가 화면에서 조정한 값이 배포 때마다 기본값으로
 * 되돌아가면 이 구조 전체가 무의미해진다. 코드가 원본인 것은 메타데이터(설명·범위·표시명)뿐이며
 * 그것만 동기화한다.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class SystemConfigBootstrap implements ApplicationRunner {

    private final List<ConfigDefinitionProvider> providers;
    private final SystemConfigRepository configRepository;
    private final SystemConfigService configService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<ConfigDefinition> definitions = providers.stream()
                .flatMap(provider -> provider.definitions().stream())
                .toList();

        Map<String, SystemConfig> existing = configRepository.findAll().stream()
                .collect(Collectors.toMap(SystemConfig::getConfigKey, Function.identity()));

        int created = 0;
        int synced = 0;
        for (ConfigDefinition definition : definitions) {
            SystemConfig config = existing.get(definition.key());
            if (config == null) {
                configRepository.save(SystemConfig.from(definition));
                created++;
            } else {
                config.syncMetadata(definition);
                synced++;
            }
        }

        configService.reload();
        log.info("시스템 설정 준비 — 신규 {}건, 메타 동기화 {}건, 정의 총 {}건",
                created, synced, definitions.size());
    }
}
