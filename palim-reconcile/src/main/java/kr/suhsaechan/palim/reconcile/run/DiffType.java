package kr.suhsaechan.palim.reconcile.run;

/**
 * 차이의 종류.
 *
 * <p>미매칭을 «실패» 가 아니라 결과의 한 유형으로 둔다. 매칭 안 된 품목 하나 때문에 대조
 * 전체를 중단하면 나머지 결과도 못 보게 되고, 그러면 사람이 매칭을 끝낼 때까지 대조를 아예
 * 쓸 수 없다.
 */
public enum DiffType {
    /** 좌측(보통 전산)이 더 많다. */
    LEFT_MORE,
    /** 우측(보통 물류)이 더 많다. */
    RIGHT_MORE,
    /** 좌측에만 있고 아직 어느 단위에도 속하지 않은 품목. */
    UNMATCHED_LEFT,
    /** 우측에만 있고 아직 어느 단위에도 속하지 않은 품목. */
    UNMATCHED_RIGHT
}
