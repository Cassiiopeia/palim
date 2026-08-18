package kr.suhsaechan.palim.reconcile.match;

import java.util.List;

/**
 * 여러 품목을 묶을 때 <b>그 묶음을 뭐라 부를지.</b>
 *
 * <p>처음에는 첫 품목의 이름을 그대로 썼고, 그래서 로트 넷을 묶은 묶음이 「클래식 850g
 * (27.03.16)」 이 되었다 — 로트 하나의 날짜가 묶음 전체를 대표했다. 공통 부분만 쓰도록 고쳤는데
 * <b>그것도 모든 자료에 옳지는 않다.</b> 품명이 아예 다른 두 시스템에서는 공통 부분이 「초콜」
 * 같은 토막으로 남는다.
 *
 * <p>그래서 <b>코드가 정하지 않는다.</b> 어느 쪽도 안 맞으면 사람이 매번 짓는다.
 */
public enum UnitNameRule {

    /** 담긴 품명들의 공통 부분. 로트가 이름에 섞여 오는 자료에서 잘 듣는다. */
    COMMON("공통 부분만 (권장)"),

    /** 첫 품목의 이름 그대로. 품명이 양쪽에서 아예 다를 때는 이쪽이 덜 이상하다. */
    FIRST_ITEM("첫 품목 이름 그대로"),

    /** 코드가 짓지 않는다. 목록에 「이름 없음」 으로 뜨므로 사람이 반드시 짓게 된다. */
    MANUAL("짓지 않음 — 내가 직접");

    private final String label;

    UnitNameRule(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 주소나 폼에 이상한 값이 와도 화면이 깨지지 않는다. */
    public static UnitNameRule of(String raw) {
        if (raw == null || raw.isBlank()) {
            return COMMON;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            return COMMON;
        }
    }

    /**
     * 이 규칙으로 이름을 짓는다.
     *
     * @return 지을 이름. {@link #MANUAL} 이거나 재료가 없으면 빈 문자열
     */
    public String nameOf(List<String> leftNames, List<String> rightNames) {
        return switch (this) {
            case COMMON -> CommonName.of(leftNames, rightNames);
            case FIRST_ITEM -> leftNames.isEmpty()
                    ? (rightNames.isEmpty() ? "" : rightNames.getFirst())
                    : leftNames.getFirst();
            case MANUAL -> "";
        };
    }
}
