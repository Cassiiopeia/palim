package kr.suhsaechan.palim.reconcile.rule;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 품명을 견줄 수 있는 모양으로 다듬는다.
 *
 * <p>규칙을 {@code sortOrder} 순서대로 적용한다. 순서가 바뀌면 결과가 달라지므로 — 괄호를 떼기
 * 전에 공백을 지우면 괄호 규칙이 안 맞는다 — 순서를 정의가 갖는다.
 *
 * <p><b>결과를 확정으로 쓰지 않는다.</b> 이것은 사람에게 보여줄 후보를 좁히는 용도다. 정규화가
 * 같다고 자동으로 묶어 버리면, 규칙이 틀렸을 때 엉뚱한 품목을 합쳐 놓고 "재고가 맞는다"고
 * 보고한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NormalizationEngine {

    /**
     * 다듬을 이름의 최대 길이.
     *
     * <p>사람이 정규식을 직접 넣는 화면이라 <b>되돌아가는 패턴</b>이 들어올 수 있다. 입력이
     * 길수록 그 비용이 폭발하므로 길이를 자른다 — 품명이 이보다 길 일은 없다.
     */
    private static final int MAX_INPUT = 300;

    private final NormalizationRuleRepository rules;

    /** 컴파일 결과를 재사용한다. 품목 수천 건을 돌리면서 매번 컴파일하면 그 자체가 비용이다. */
    private final Map<String, Pattern> compiled = new ConcurrentHashMap<>();

    /**
     * @param rawName 원본 품명. 비어 있으면 빈 문자열
     * @return 견줄 수 있게 다듬은 이름. 소문자로 맞춘다
     */
    @Transactional(readOnly = true)
    public String normalize(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "";
        }
        String value = rawName.length() > MAX_INPUT ? rawName.substring(0, MAX_INPUT) : rawName;

        for (NormalizationRule rule : rules.findByIsActiveTrueOrderBySortOrder()) {
            Pattern pattern = patternOf(rule);
            if (pattern == null) {
                continue;
            }
            try {
                value = pattern.matcher(value).replaceAll(rule.getReplacement());
            } catch (RuntimeException e) {
                // 치환 문자열의 $1 같은 참조가 어긋나면 던진다. 규칙 하나 때문에 매칭 화면
                // 전체가 열리지 않으면 사람이 그 규칙을 고칠 수도 없다.
                log.warn("정규화 규칙을 건너뛴다 — {}", rule.getName(), e);
            }
        }
        // 남은 공백은 지운다. 원천마다 띄어쓰기가 제각각이라 이것만으로도 상당수가 맞는다.
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 여러 이름을 한 번에. 규칙 조회를 한 번만 하려는 것이 아니라 호출부를 단순하게 하려는 것이다. */
    @Transactional(readOnly = true)
    public List<String> normalizeAll(List<String> rawNames) {
        return rawNames.stream().map(this::normalize).toList();
    }

    private Pattern patternOf(NormalizationRule rule) {
        return compiled.computeIfAbsent(rule.getPattern(), source -> {
            try {
                return Pattern.compile(source);
            } catch (PatternSyntaxException e) {
                log.warn("정규식이 잘못된 규칙을 건너뛴다 — {}", rule.getName(), e);
                return null;
            }
        });
    }

    /** 규칙을 고쳤으면 캐시를 비운다. 안 그러면 옛 패턴으로 계속 돈다. */
    public void clearCache() {
        compiled.clear();
    }
}
