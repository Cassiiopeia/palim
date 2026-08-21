package kr.suhsaechan.palim.reconcile.filter;

/**
 * 어느 원천에 거는 조건인가.
 *
 * <p>좌·우를 나누는 이유는 두 원천이 서로 다른 것을 담기 때문이다 — 전산에는 창고가 여럿이고
 * 맡긴 곳에는 하나뿐인 일이 흔하다. 한쪽만 좁히는 것이 정상이다.
 */
public enum FilterSide {
    LEFT,
    RIGHT
}
