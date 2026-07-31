package kr.suhsaechan.palim.web.monitor;

/**
 * 채널 수집 상태 판정 (#30).
 *
 * <p>판정 순서가 중요하다 — 자동 중단은 비활성의 부분집합이므로 먼저 검사해야 하고,
 * 실패 기록이 있는 채널을 지연으로 뭉뚱그리면 오류 내용이 화면에서 사라진다.
 */
public enum CollectHealth {

    /** 연속 실패 임계 도달로 시스템이 수집을 중단했다. 가장 시급하다. */
    AUTO_DISABLED("자동 중단", "error"),

    /** 발주자가 껐거나 인증정보가 등록되지 않았다. */
    DISABLED("비활성", "ghost"),

    /** 마지막 수집이 실패했다. */
    FAILING("실패", "error"),

    /**
     * 예정 시각을 크게 넘겼는데 수집 기록이 없다.
     *
     * <p><b>스케줄러가 죽었다는 신호다.</b> 실패는 기록이라도 남지만, 프로세스 정지·스레드
     * 고갈은 아무 기록 없이 멈춘다. 그 상태를 잡는 것이 이 화면의 존재 이유다.
     */
    STALE("지연", "warning"),

    /** 활성화됐지만 아직 첫 수집 전이다. */
    WAITING_FIRST("첫 수집 대기", "info"),

    /** 정상. */
    HEALTHY("정상", "success");

    private final String displayName;
    private final String badgeClass;

    CollectHealth(String displayName, String badgeClass) {
        this.displayName = displayName;
        this.badgeClass = badgeClass;
    }

    public String displayName() {
        return displayName;
    }

    /** daisyUI badge 색상 접미사 (badge-error 등). */
    public String badgeClass() {
        return badgeClass;
    }

    /** 발주자 조치가 필요한 상태인지. 화면 상단 요약에 쓴다. */
    public boolean needsAttention() {
        return this == AUTO_DISABLED || this == FAILING || this == STALE;
    }
}
