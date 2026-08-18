package kr.suhsaechan.palim.reconcile.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    private final Map<String, Optional<Pattern>> compiled = new ConcurrentHashMap<>();

    /**
     * @param rawName 원본 품명. 비어 있으면 빈 문자열
     * @return 견줄 수 있게 다듬은 이름. 소문자로 맞춘다
     */
    @Transactional(readOnly = true)
    public String normalize(String rawName) {
        return apply(rules.findByIsActiveTrueOrderBySortOrder(), rawName);
    }

    /**
     * 그 원천에 거는 규칙만 적용한다.
     *
     * @param source 스냅샷의 {@code source}. 원천을 가리지 않는 규칙은 언제나 함께 걸린다
     */
    @Transactional(readOnly = true)
    public String normalize(String rawName, String source) {
        return apply(activeFor(source), rawName);
    }

    /** 그 원천에 걸리는 규칙만 골라 준다. 원천을 지정하지 않은 규칙은 어디에나 걸린다. */
    @Transactional(readOnly = true)
    public List<NormalizationRule> activeFor(String source) {
        return rules.findByIsActiveTrueOrderBySortOrder().stream()
                .filter(rule -> rule.appliesTo(source))
                .toList();
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
    public static final class Batch {

        private final NormalizationEngine engine;
        private final List<NormalizationRule> rules;

        /**
         * 원천마다 거른 결과를 재사용한다.
         *
         * <p>대사는 품목 수천 건을 한 요청에서 다듬는다. 이름 하나마다 규칙 목록을 걸러 내면
         * 그 거르기가 다듬기보다 비싸진다 — 원천은 두세 개뿐이므로 한 번만 거르면 된다.
         */
        private final Map<String, List<NormalizationRule>> bySource = new ConcurrentHashMap<>();

        Batch(NormalizationEngine engine, List<NormalizationRule> rules) {
            this.engine = engine;
            this.rules = rules;
        }

        /**
         * 원천을 가리지 않고 모든 규칙을 건다.
         *
         * <p><b>입력을 감싼다.</b> 여기 오는 규칙은 사람이 넣은 것이고, 미리보기를 통과했다고
         * 안전한 것이 아니다 — 표본 열두 개로는 되돌아가는 패턴이 드러나지 않는다. 감싸 두면
         * 부르는 쪽이 제한 시간을 걸었을 때 실제로 멈춘다.
         */
        public String normalize(String rawName) {
            return engine.apply(rules, rawName, RegexGuard::guard);
        }

        /**
         * 그 원천에 걸리는 규칙만 건다.
         *
         * <p><b>대사가 이 길로 와야 원천별 규칙이 뜻을 갖는다.</b> 원천을 안 넘기면 한쪽에만
         * 걸어 둔 규칙이 반대쪽 이름까지 바꿔, 화면에서 원천을 고른 것이 아무 일도 하지 않는다.
         *
         * @param source 스냅샷의 {@code source}. {@code null} 이면 모든 규칙을 건다
         */
        public String normalize(String rawName, String source) {
            if (source == null) {
                return normalize(rawName);
            }
            return engine.apply(
                    bySource.computeIfAbsent(source, key -> rules.stream()
                            .filter(rule -> rule.appliesTo(key))
                            .toList()),
                    rawName, RegexGuard::guard);
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
        return finish(value);
    }

    /**
     * 규칙을 다 건 뒤 언제나 하는 마무리.
     *
     * <p>남은 공백을 지우고 소문자로 맞춘다. 원천마다 띄어쓰기가 제각각이라 이것만으로도 상당수가
     * 맞는다. <b>규칙 사이에 끼우지 않는 이유</b>는, 중간에 공백이 사라지면 공백을 포함한 정규식
     * (예: {@code \s*\(...\)})이 다음 규칙부터 안 걸리기 때문이다.
     */
    private static String finish(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 규칙을 하나씩 걸며 <b>어느 규칙이 값을 실제로 바꿨는지</b> 함께 돌려준다.
     *
     * <p>규칙 화면이 「이 규칙이 몇 개를 바꿨나」 를 세는 데 쓴다. 규칙 하나만 따로 걸어 세면
     * 실제와 다른 값이 나온다 — 앞 규칙이 이미 바꿔 놓은 이름에 걸리는 규칙이 있기 때문이다.
     * 그래서 <b>한 번 도는 동안</b> 기록한다.
     */
    public Trace trace(List<NormalizationRule> active, String rawName) {
        return trace(active, rawName, value -> value);
    }

    /**
     * @param guard 입력을 감싸는 것. 사람이 넣은 정규식을 돌리는 자리에서는 인터럽트를 확인하는
     *              입력으로 감싸야 제한 시간이 실제로 먹는다 ({@link RegexGuard})
     */
    public Trace trace(List<NormalizationRule> active, String rawName,
                       UnaryOperator<CharSequence> guard) {
        if (rawName == null || rawName.isBlank()) {
            return new Trace("", List.of());
        }
        String value = rawName.length() > MAX_INPUT ? rawName.substring(0, MAX_INPUT) : rawName;
        List<UUID> changedBy = new ArrayList<>();

        for (NormalizationRule rule : active) {
            Pattern pattern = patternOf(rule);
            if (pattern == null) {
                continue;
            }
            try {
                String next = pattern.matcher(guard.apply(value))
                        .replaceAll(rule.getReplacement());
                if (!next.equals(value)) {
                    changedBy.add(rule.getId());
                }
                value = next;
            } catch (RuntimeException e) {
                log.warn("정규화 규칙을 건너뛴다 — {}", rule.getName(), e);
            }
        }
        return new Trace(finish(value), changedBy);
    }

    /**
     * 한 이름을 다듬은 결과와 그 과정.
     *
     * @param result    다듬은 이름
     * @param changedBy 이 이름을 실제로 바꾼 규칙들. 순서는 적용 순서
     */
    public record Trace(String result, List<UUID> changedBy) {
    }

    /** 여러 이름을 한 번에. 규칙 조회는 한 번만 한다. */
    @Transactional(readOnly = true)
    public List<String> normalizeAll(List<String> rawNames) {
        Batch batch = batch();
        return rawNames.stream().map(batch::normalize).toList();
    }

    /**
     * 컴파일한 정규식을 꺼낸다.
     *
     * <p><b>실패도 캐시한다.</b> {@code ConcurrentHashMap.computeIfAbsent} 는 {@code null} 을
     * 저장하지 않으므로, 잘못된 정규식이 DB 에 남아 있으면 <b>이름 하나마다</b> 다시 컴파일해
     * 예외를 던지고 경고를 찍는다. 품목 수천 건을 도는 자리에서는 그 자체가 느려지고, 로그가
     * 같은 줄로 수천 번 덮여 정작 봐야 할 것이 밀려난다.
     */
    private Pattern patternOf(NormalizationRule rule) {
        return compiled.computeIfAbsent(rule.getPattern(), source -> {
            try {
                return Optional.of(Pattern.compile(source));
            } catch (PatternSyntaxException e) {
                log.warn("정규식이 잘못된 규칙을 건너뛴다 — {}", rule.getName(), e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** 규칙을 고쳤으면 캐시를 비운다. 안 그러면 옛 패턴으로 계속 돈다. */
    public void clearCache() {
        compiled.clear();
    }
}
