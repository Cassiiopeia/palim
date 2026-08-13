package kr.suhsaechan.palim.web.setup;

/**
 * 준비 단계 한 줄.
 *
 * <p>화면은 이 목록을 그대로 그린다. <b>지금 어디까지 됐고 다음에 뭘 해야 하는지</b>가 한눈에
 * 보여야 처음 오는 사람이 따라갈 수 있다.
 *
 * @param order    순서
 * @param title    단계 이름
 * @param state    상태
 * @param detail   현재 상황 (예: "정식 키 · 어제 확인")
 * @param action   다음에 할 일. 끝난 단계는 {@code null}
 * @param link     그 일을 하러 가는 곳. 아직 만들지 않은 화면이면 {@code null}
 */
public record SetupStep(int order, String title, State state, String detail, String action,
                        String link) {

    public enum State {
        /** 끝났다. */
        DONE,
        /** 지금 손봐야 한다. */
        ATTENTION,
        /** 앞 단계가 끝나야 할 수 있다. */
        WAITING,
        /** 아직 만들지 않은 기능. 숨기지 않고 보여준다 — 전체 그림을 알아야 지금 위치가 이해된다. */
        NOT_READY
    }

    public boolean isDone() {
        return state == State.DONE;
    }

    public boolean isActionable() {
        return state == State.ATTENTION && link != null;
    }
}
