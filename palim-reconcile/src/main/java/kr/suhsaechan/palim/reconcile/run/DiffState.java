package kr.suhsaechan.palim.reconcile.run;

/**
 * 차이의 상태.
 *
 * <p>한 번 보고 확정하지 않는다. 반영 지연이면 다음 회차에 사라지고, 진짜 불일치만 남는다.
 * 첫 회차부터 알리면 매일 헛알림이 가고 그러면 진짜 알림도 안 보게 된다.
 */
public enum DiffState {
    /** 처음 관찰됐다. 반영 지연일 수 있어 알리지 않는다. */
    OBSERVING,
    /** 다음 실행에도 같은 방향으로 남았다. 시간으로 설명되지 않는다. */
    CONFIRMED,
    /** 사람이 처리했다. */
    RESOLVED,
    /** 알면서 두기로 했다. */
    IGNORED
}
