package kr.suhsaechan.palim.reconcile.rule;

import java.util.List;

/**
 * 자주 쓰는 다듬기 규칙의 <b>출발점</b>.
 *
 * <p>고를 수 있는 항목만 제공하면 그 목록에 없는 표기 습관을 가진 회사는 이 제품을 쓸 수 없다.
 * 품목명 체계는 회사마다 다르고 우리가 미리 다 알 수 없다 — 그래서 정규식 입력칸을 없애지
 * 않는다. 이 목록은 <b>백지에서 시작하지 않게 해 주는 것</b>일 뿐이다.
 *
 * <p>누르면 정규식이 입력칸에 채워지고, 거기서 사람이 고친다. 넣은 뒤에도 다른 규칙과 똑같이
 * 고치고 끄고 지울 수 있다 — 프리셋으로 들어왔다는 흔적은 남지 않는다.
 *
 * <p>여기 담는 것은 <b>어느 회사에나 통하는 일반적인 다듬기</b>뿐이다. 특정 거래처의 표기
 * 습관에 맞춘 것은 넣지 않는다. 그것을 넣는 순간 이 제품은 그 회사 전용이 된다.
 */
public final class NormalizationPresets {

    private NormalizationPresets() {
    }

    /**
     * 프리셋 하나.
     *
     * @param key         화면에서 버튼을 구분할 값
     * @param name        규칙 이름의 기본값. 사람이 고칠 수 있다
     * @param pattern     찾을 것(정규식)
     * @param replacement 바꿀 것. 빈 문자열이면 지운다
     * @param example     이 규칙이 무엇을 하는지 보여주는 실제 예. 「무엇을 넣는 칸인지」
     *                    설명하는 문장보다 예시 한 줄이 빠르다
     */
    public record Preset(String key, String name, String pattern, String replacement,
                         String example) {
    }

    private static final List<Preset> ALL = List.of(
            new Preset("paren", "괄호와 그 안의 내용을 뺀다",
                    "\\([^)]*\\)", "",
                    "클래식 227g (26.10.17) → 클래식 227g"),

            new Preset("bracket", "대괄호와 그 안의 내용을 뺀다",
                    "\\[[^\\]]*\\]", "",
                    "클래식 227g [26.10.17] → 클래식 227g"),

            new Preset("after-underscore", "밑줄 뒤를 뺀다",
                    "_.*$", "",
                    "초콜릿 프로틴바 70g_26.12.12 → 초콜릿 프로틴바 70g"),

            new Preset("date", "이름에 박힌 날짜를 뺀다",
                    "\\d{2,4}[.\\-/]\\d{1,2}[.\\-/]\\d{1,2}", "",
                    "노슈거 198g 26.11.26 → 노슈거 198g"),

            new Preset("separator", "이음 기호를 뺀다",
                    "[-_/]+", "",
                    "클래식-227g/기본 → 클래식227g기본"),

            new Preset("size", "용량 표기를 뺀다",
                    "\\d+(\\.\\d+)?\\s*(g|kg|ml|l|ea|개|정|포)\\b", "",
                    "초콜릿 프로틴바 70g → 초콜릿 프로틴바"),

            new Preset("pack", "묶음 개수 표기를 뺀다",
                    "[xX]\\s*\\d+\\s*(ea|개)?$", "",
                    "프로틴바 70gx12ea → 프로틴바 70g"));

    public static List<Preset> all() {
        return ALL;
    }
}
