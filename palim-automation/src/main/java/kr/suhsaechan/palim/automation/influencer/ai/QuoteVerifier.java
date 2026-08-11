package kr.suhsaechan.palim.automation.influencer.ai;

import java.text.Normalizer;
import java.util.List;

/**
 * AI 가 제시한 인용이 실제 입력에 있는지 대조한다.
 *
 * <p><b>이것이 AI 심사의 유일한 방어선이다.</b> 스키마로 근거를 강제해도 AI 는 그럴듯한 문장을
 * 지어낼 수 있고, 존재하지 않는 댓글을 인용하면 사람은 확인할 방법이 없다. 인용이 원문에 없으면
 * 그 주장을 코드가 버림으로써, 지어낸 근거가 화면에 도달하지 못하게 한다.
 *
 * <p>정확히 일치를 요구하지는 않는다. AI 는 인용하면서 공백·줄바꿈·말줄임을 정리하기 때문이다.
 * 대신 <b>정규화 후 부분 문자열</b> 을 본다 — 원문에 없는 내용을 만들어내는 것은 막으면서,
 * 서식 차이로 진짜 근거를 버리지는 않는 선이다.
 */
public final class QuoteVerifier {

    /**
     * 검증에 쓰는 최소 길이.
     *
     * <p>너무 짧은 인용("네", "좋아요")은 어떤 원문에도 우연히 포함되어 검증이 무의미해진다.
     */
    private static final int MIN_QUOTE_LENGTH = 6;

    private QuoteVerifier() {
    }

    /**
     * @param quote   AI 가 제시한 인용
     * @param sources 원문 목록(자막·댓글). null 항목은 무시한다
     * @return 원문 중 하나에 포함되면 true
     */
    public static boolean isGrounded(String quote, List<String> sources) {
        if (quote == null || quote.isBlank()) {
            return false;
        }
        String needle = normalize(quote);
        if (needle.length() < MIN_QUOTE_LENGTH) {
            return false;
        }
        return sources.stream()
                .filter(source -> source != null && !source.isBlank())
                .map(QuoteVerifier::normalize)
                .anyMatch(haystack -> haystack.contains(needle));
    }

    /**
     * 비교용 정규화.
     *
     * <p>유니코드 정규화(NFKC)를 먼저 한다 — 한글은 자모 분리형(NFD)과 완성형(NFC)이 눈으로는
     * 같아 보이지만 문자열로는 다르다. 그다음 공백을 모두 제거하고 소문자로 낮춘다.
     * 서식 차이만 지우고 내용은 그대로 둔다.
     */
    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase();
    }
}
