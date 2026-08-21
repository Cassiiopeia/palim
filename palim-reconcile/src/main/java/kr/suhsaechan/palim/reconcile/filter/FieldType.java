package kr.suhsaechan.palim.reconcile.filter;

/**
 * 걸 수 있는 칸의 값 종류.
 *
 * <p><b>연산자를 칸마다 고르지 않고 타입이 정한다.</b> 칸마다 고르면 그 판단이 칸 수만큼
 * 늘어나고, 늘어난 만큼 「왜 이 칸엔 이게 없지」 가 생긴다. 타입은 넷뿐이라 빠짐없이 채울 수 있다.
 *
 * <p>새 칸을 붙이는 데는 코드가 필요 없다 — 칸은 담긴 자료에서 나온다. 코드가 필요한 것은
 * <b>새 타입</b>뿐이고, 그것이 이 설계의 확장 지점이 칸에 있다는 뜻이다.
 */
public enum FieldType {
    TEXT,
    NUMBER,
    DATE,
    BOOL
}
