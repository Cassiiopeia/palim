package kr.suhsaechan.palim.reconcile.run;

/**
 * 이 회차를 <b>무엇이</b> 시작했나.
 *
 * <p>구분이 없으면 사람이 「지금 맞춰 보기」 를 한 번 누른 것 때문에 <b>그날 자동 대조가
 * 통째로 건너뛰어진다</b> — 정해진 시각에 도는 쪽이 「오늘 이미 돌았나」 를 이력으로 판단하기
 * 때문이다.
 */
public enum ReconcileTrigger {

    /** 사람이 화면에서 눌렀다. */
    MANUAL,

    /** 정해진 시각이 되어 저절로 돌았다. */
    SCHEDULED
}
