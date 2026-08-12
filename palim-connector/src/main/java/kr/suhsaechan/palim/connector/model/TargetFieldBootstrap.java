package kr.suhsaechan.palim.connector.model;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 표준 모델의 필드 정의 등록.
 *
 * <p>부팅 시 <b>없는 필드만</b> 추가한다. 이미 있는 필드는 건드리지 않으므로 재기동이 안전하고,
 * 화면에서 표시명을 고쳤어도 배포가 되돌리지 않는다 — {@code SystemConfig} 부트스트랩과 같은
 * 원칙이다.
 *
 * <p>모델이 아직 없으면(마이그레이션 전 등) 건너뛴다. 부팅을 막을 이유가 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetFieldBootstrap implements ApplicationRunner {

    private final List<StandardModelFields> providers;
    private final TargetModelRepository modelRepository;
    private final TargetFieldRepository fieldRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        providers.forEach(this::register);
    }

    private void register(StandardModelFields provider) {
        List<TargetModel> models = modelRepository.findAll().stream()
                .filter(model -> provider.modelCode().equals(model.getCode()))
                .toList();

        if (models.isEmpty()) {
            log.warn("표준 모델이 없어 필드 등록을 건너뜁니다 — {}", provider.modelCode());
            return;
        }

        models.forEach(model -> registerFor(model, provider.fields()));
    }

    /** 테넌트마다 같은 코드의 모델이 있을 수 있어 모델 단위로 등록한다. */
    private void registerFor(TargetModel model, List<FieldDefinition> definitions) {
        Set<String> existing = fieldRepository.findByTargetModelIdOrderBySortOrder(model.getId())
                .stream()
                .map(TargetField::getFieldKey)
                .collect(Collectors.toSet());

        List<TargetField> added = new java.util.ArrayList<>();
        for (int order = 0; order < definitions.size(); order++) {
            FieldDefinition definition = definitions.get(order);
            if (existing.contains(definition.key())) {
                continue;
            }
            added.add(TargetField.of(model.getTenantId(), model.getId(), definition.key(),
                    definition.displayName(), definition.dataType(), definition.required(),
                    null, order));
        }

        if (!added.isEmpty()) {
            fieldRepository.saveAll(added);
            log.info("표준 모델 필드 등록 — {} {}개", model.getCode(), added.size());
        }
    }
}
