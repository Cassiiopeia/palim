package kr.suhsaechan.palim.connector.define;

/**
 * 자료가 <b>어느 길로</b> 들어오나.
 *
 * <p>공개 API 가 없는 원천은 상대 화면이 쓰는 경로를 그대로 흉내 내서 가져온다. 그런데
 * <b>상대 사이트가 바뀌면 그날로 깨진다</b> — 로그인 방식, 조회 주소, 응답 모양 어느 하나만
 * 달라져도 자동 수집이 멈춘다. 그때 사람이 파일을 받아 올려 계속 돌릴 수 있어야 업무가 안
 * 멈춘다.
 *
 * <p><b>파일은 「같은 원천 이름」 으로 들어간다.</b> 별도 연동을 새로 만들면 원천 이름이 달라져
 * 그동안 묶어 둔 품목이 통째로 무용지물이 된다 — 급할 때 쓰는 길인데 그때 묶기부터 다시 하라는
 * 셈이다.
 *
 * <p>그런데 <b>엑셀 열 이름은 API 칸 이름과 다르다</b>(API 는 {@code stock_qty}, 엑셀은
 * 「재고수량」). 그래서 칸 맞추기를 길마다 따로 둔다.
 */
public enum Intake {

    /** 스스로 가져오는 길. API 호출이나 화면 경로 흉내. */
    AUTO("자동 수집용"),

    /** 사람이 받아 올리는 길. 자동 수집이 깨졌을 때의 우회로다. */
    FILE("파일 업로드용");

    private final String label;

    Intake(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 주소를 손으로 고쳐 이상한 값이 와도 화면이 깨지지 않는다. */
    public static Intake of(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            return AUTO;
        }
    }
}
