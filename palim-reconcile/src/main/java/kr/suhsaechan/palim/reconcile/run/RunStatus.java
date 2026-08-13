package kr.suhsaechan.palim.reconcile.run;

/** 대조 실행 상태. */
public enum RunStatus {
    RUNNING,
    SUCCESS,
    /** 기준 시각이 어긋났거나 비교할 재고가 없다. 사유는 실행 기록에 남는다. */
    FAILED
}
