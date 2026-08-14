package kr.suhsaechan.palim.web.home;

import java.time.Instant;

/**
 * 홈에 띄우는 오늘 한 줄.
 *
 * <p><b>「지금 볼 것」과 「지켜볼 것」과 「아직 안 이어진 품목」을 따로 센다.</b> 셋은 할 일이
 * 다르다 — 앞의 둘은 재고를 맞추는 일이고 마지막은 품목을 잇는 일이다. 한 숫자로 합치면 사장님이
 * 재고를 뒤지다가 정작 할 일이 품목 잇기였다는 것을 나중에 안다.
 */
public record TodayReconcile(boolean ran, Instant at, int confirmed, int observing, int unmatched) {

    public static TodayReconcile none() {
        return new TodayReconcile(false, null, 0, 0, 0);
    }

    /** 지금 손댈 것이 있는가. */
    public boolean needsAttention() {
        return confirmed > 0;
    }
}
