package kr.suhsaechan.palim.reconcile.rule;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
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
        return apply(rules.findByIsActiveTrueOrderBySortOrder(), rawName);
    }

    /**
     * 규칙을 <b>한 번만 읽어</b> 여러 이름을 다듬는 도구를 만든다.
     *
     * <p>{@link #normalize} 는 부를 때마다 규칙을 조회한다. 품목 하나에 한 번이면 괜찮지만,
     * 목록 화면처럼 <b>수천 품목을 한 요청에서 다듬는</b> 자리에서는 그만큼 조회가 반복된다 —
     * 품목이 늘수록 화면이 느려지는데 원인이 화면 코드 어디에도 안 보인다.
     */
    @Transactional(readOnly = true)
    public Batch batch() {
        return new Batch(this, rules.findByIsActiveTrueOrderBySortOrder());
    }

    /** 규칙을 들고 있는 다듬기 도구. 한 요청 안에서만 쓴다 — 규칙을 고치면 다시 만들어야 한다. */
    public record Batch(NormalizationEngine engine, List<NormalizationRule> rules) {

        public String normalize(String rawName) {
            return engine.apply(rules, rawName);
        }
    }

    /**
     * 주어진 규칙들을 순서대로 적용한다.
     *
     * @param active 적용할 규칙. 순서가 곧 적용 순서다
     */
    public String apply(List<NormalizationRule> active, String rawName) {
        return apply(active, rawName, value -> value);
    }

    /**
     * 입력을 <b>감싼 뒤</b> 규칙을 건다.
     *
     * <p>{@code guard} 가 있는 이유는 미리보기 화면 때문이다. 거기서는 사람이 방금 타이핑한
     * 정규식이 돌아가므로 <b>되돌아가는 패턴</b>이 들어올 수 있는데, 자바의 {@code Matcher} 는
     * 인터럽트를 보지 않아 그냥 두면 취소가 먹지 않는다. 입력이 글자를 내줄 때마다 확인하게
     * 감싸면 그때만 멈출 수 있다. 평소 경로는 감싸지 않는다 — 규칙은 이미 검증된 것이다.
     */
    public String apply(List<NormalizationRule> active, String rawName,
                        UnaryOperator<CharSequence> guard) {
        if (rawName == null || rawName.isBlank()) {
            return "";
        }
        String value = rawName.length() > MAX_INPUT ? rawName.substring(0, MAX_INPUT) : rawName;

        for (NormalizationRule rule : active) {
            Pattern pattern = patternOf(rule);
            if (pattern == null) {
                continue;
            }
            try {
                value = pattern.matcher(guard.apply(value)).replaceAll(rule.getReplacement());
            } catch (RuntimeException e) {
                // 치환 문자열의 $1 같은 참조가 어긋나면 던진다. 규칙 하나 때문에 매칭 화면
                // 전체가 열리지 않으면 사람이 그 규칙을 고칠 수도 없다.
                log.warn("정규화 규칙을 건너뛴다 — {}", rule.getName(), e);
            }
        }
        // 남은 공백은 지운다. 원천마다 띄어쓰기가 제각각이라 이것만으로도 상당수가 맞는다.
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 여러 이름을 한 번에. 규칙 조회는 한 번만 한다. */
    @Transactional(readOnly = true)
    public List<String> normalizeAll(List<String> rawNames) {
        Batch batch = batch();
        return rawNames.stream().map(batch::normalize).toList();
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
