package kr.suhsaechan.palim.automation.influencer.collect;

/**
 * 한글 비율 계산.
 *
 * <p>국내 채널 선별에 쓴다. 채널의 {@code country} 는 공개하지 않는 채널이 많아서 그것만으로는
 * 절반 이상을 놓친다. 제목·설명의 한글 비율이 보조 판정 기준이다.
 *
 * <p>공백·숫자·기호는 분모에서 뺀다 — 이모지와 특수문자가 많은 제목에서 한글 비율이 부당하게
 * 낮게 나오기 때문이다. 영문 채널명을 쓰는 국내 크리에이터가 있어 이 판정만으로 탈락시키지는
 * 않고, {@code country=KR} 과 OR 조건으로 쓴다.
 */
public final class KoreanTextRatio {

    private KoreanTextRatio() {
    }

    /** @return 문자(한글+영문) 중 한글 비율. 판정할 문자가 없으면 0 */
    public static double of(String... texts) {
        int korean = 0;
        int letters = 0;

        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (isHangul(c)) {
                    korean++;
                    letters++;
                } else if (Character.isLetter(c)) {
                    letters++;
                }
            }
        }
        return letters == 0 ? 0 : (double) korean / letters;
    }

    private static boolean isHangul(char c) {
        return (c >= 0xAC00 && c <= 0xD7A3)   // 완성형 음절
                || (c >= 0x1100 && c <= 0x11FF)  // 자모
                || (c >= 0x3130 && c <= 0x318F); // 호환 자모
    }
}
