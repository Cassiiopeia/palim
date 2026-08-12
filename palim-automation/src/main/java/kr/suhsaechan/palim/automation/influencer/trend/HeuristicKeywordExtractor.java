package kr.suhsaechan.palim.automation.influencer.trend;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 형태소 분석기 없이 키워드를 뽑는다.
 *
 * <p>영상 제목은 자연 문장이 아니라 <b>키워드 나열에 가깝다</b>("겨울 캠핑 난로 추천 TOP5").
 * 그래서 조사 절단과 불용어 제거만으로도 실용적인 결과가 나온다.
 *
 * <p>단어 하나만 세지 않고 <b>인접 두 단어 묶음(bigram)도 함께</b> 센다. "캠핑"과 "장비"가
 * 각각 흔한 말이어도 "캠핑 장비"가 갑자기 늘었다면 그것이 트렌드이며, 단어 단위로만 세면
 * 이 신호가 보이지 않는다. 발굴 시드로 쓸 때도 두 단어 조합이 롱테일이라 유용하다.
 */
@Component
public class HeuristicKeywordExtractor implements KeywordExtractor {

    /** 한글·영문·숫자만 남긴다. 이모지·특수문자는 구분자로 취급한다. */
    private static final Pattern SEPARATOR = Pattern.compile("[^가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]+");

    /** 순수 숫자 토큰. "2026", "TOP5" 의 숫자 부분처럼 의미가 없다. */
    private static final Pattern NUMERIC = Pattern.compile("^[0-9]+$");

    /**
     * 잘라낼 조사·어미.
     *
     * <p>긴 것을 먼저 본다 — "에서"를 "서"보다 먼저 잘라야 "캠핑장에서"가 "캠핑장"이 된다.
     * 명사 자체가 이 글자로 끝나는 경우(예: "고기", "이야기")를 잘못 자르지 않도록
     * <b>남는 부분이 2글자 이상일 때만</b> 적용한다.
     */
    private static final List<String> PARTICLES = List.of(
            "으로부터", "에서부터", "이라는", "라는", "에서", "에게", "으로", "까지", "부터",
            "보다", "처럼", "마다", "밖에", "이나", "과의", "와의", "의", "가", "이", "은", "는",
            "을", "를", "에", "도", "만", "로", "와", "과");

    /**
     * 불용어.
     *
     * <p>두 부류다 — 어디에나 붙는 영상 관용어("리뷰", "브이로그", "추천")와 강조어("진짜",
     * "완전"). 관용어를 남기면 <b>모든 카테고리의 1위가 "리뷰"</b>가 되어 트렌드 보드가
     * 아무것도 말해주지 않는다.
     *
     * <p>다만 bigram 에서는 이 말들이 유용하다("난로 추천"). 그래서 단어 단위에서만 거른다.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "영상", "브이로그", "블로그", "리뷰", "추천", "방법", "정리", "소개", "공개", "후기",
            "모음", "특집", "최신", "요즘", "오늘", "이번", "그냥", "진짜", "완전", "너무", "정말",
            "사람", "우리", "여러분", "구독", "좋아요", "채널", "댓글", "구독자", "라이브", "실시간",
            "shorts", "vlog", "review", "top", "best", "new", "official", "mv", "ep",
            "part", "full", "sub", "eng");

    /** 너무 짧은 토큰은 의미를 담지 못하고, 너무 길면 문장이라 키워드가 아니다. */
    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 12;

    @Override
    public List<String> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        for (String raw : SEPARATOR.split(text)) {
            String token = normalize(raw);
            if (token != null) {
                tokens.add(token);
            }
        }

        // 순서를 유지하며 중복 제거 — 같은 제목에 두 번 나온 단어를 두 번 세지 않는다.
        Set<String> keywords = new LinkedHashSet<>();
        for (String token : tokens) {
            if (!STOP_WORDS.contains(token)) {
                keywords.add(token);
            }
        }

        // bigram — 불용어도 포함한다. "난로 추천"은 "추천" 하나보다 훨씬 구체적이다.
        for (int i = 0; i + 1 < tokens.size(); i++) {
            String bigram = tokens.get(i) + " " + tokens.get(i + 1);
            if (bigram.length() <= MAX_LENGTH * 2) {
                keywords.add(bigram);
            }
        }

        return List.copyOf(keywords);
    }

    /** @return 키워드로 쓸 수 있는 형태. 아니면 null */
    private String normalize(String raw) {
        String token = raw.trim().toLowerCase(Locale.ROOT);
        if (token.isEmpty() || NUMERIC.matcher(token).matches()) {
            return null;
        }
        token = stripParticle(token);
        if (token.length() < MIN_LENGTH || token.length() > MAX_LENGTH) {
            return null;
        }
        return token;
    }

    /** 조사를 잘라낸다. 남는 부분이 짧아지면 명사 자체를 자른 것이므로 되돌린다. */
    private String stripParticle(String token) {
        for (String particle : PARTICLES) {
            if (token.length() > particle.length() + 1 && token.endsWith(particle)) {
                return token.substring(0, token.length() - particle.length());
            }
        }
        return token;
    }
}
