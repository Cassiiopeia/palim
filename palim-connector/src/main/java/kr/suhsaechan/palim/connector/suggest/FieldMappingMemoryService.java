package kr.suhsaechan.palim.connector.suggest;

import java.util.Map;
import kr.suhsaechan.palim.connector.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 연결을 기억한다.
 *
 * <p><b>확정할 때만 부른다.</b> 화면에서 고르는 중에 기억하면, 고민하며 이것저것 눌러 본 것까지
 * 학습해 기억이 오염되고 그 뒤로 잘못된 추천이 계속 나온다. 사람이 "이걸로 하겠다"고 결정한
 * 시점의 판단만 남긴다.
 *
 * <p>기억 저장이 실패해도 <b>매핑 확정을 막지 않는다.</b> 이것은 다음 번을 편하게 하려는 보조
 * 기능이고, 그것 때문에 본래 하려던 일이 실패하면 안 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldMappingMemoryService {

    private final FieldMappingMemoryRepository memories;

    /**
     * @param targetModel 담은 표준 모델 코드
     * @param connections 원천 칸 → 표준 항목. 연결하지 않은 칸은 값이 비어 있다
     */
    @Transactional
    public void remember(String targetModel, Map<String, String> connections) {
        connections.forEach((sourceField, targetField) -> {
            // 연결하지 않은 칸을 기억하면 빈 칸이 추천으로 되살아난다.
            if (!StringUtils.hasText(sourceField) || !StringUtils.hasText(targetField)) {
                return;
            }
            try {
                rememberOne(targetModel, sourceField, targetField);
            } catch (RuntimeException e) {
                log.warn("연결 기억 실패 — 추천만 못 하고 매핑은 정상이다: {} → {}",
                        sourceField, targetField, e);
            }
        });
    }

    private void rememberOne(String targetModel, String sourceField, String targetField) {
        // 표기가 달라도 같은 이름으로 본다. 그러지 않으면 원천마다 표기가 제각각이라
        // 같은 칸을 매번 새로 배우게 된다.
        String key = SuggestionSource.normalize(sourceField);

        memories.findBySourceFieldAndTargetModelAndTargetField(key, targetModel, targetField)
                .ifPresentOrElse(
                        FieldMappingMemory::remember,
                        () -> memories.save(FieldMappingMemory.of(
                                TenantContext.current(), key, targetModel, targetField)));
    }
}
