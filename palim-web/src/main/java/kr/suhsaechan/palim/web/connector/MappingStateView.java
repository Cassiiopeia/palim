package kr.suhsaechan.palim.web.connector;

/**
 * <b>시험이 쓰는 판</b>과 <b>적재가 쓰는 판</b>이 같은가.
 *
 * <p>둘은 일부러 다르다. 시험은 최신 판(확정 전 초안 포함)으로 돌고, 적재는 확정판으로만
 * 돈다 — 확정 전에 결과를 보는 것이 시험의 목적이고, 적재는 「어느 정의로 넣은 자료인가」 를
 * 나중에 설명할 수 있어야 하기 때문이다.
 *
 * <p><b>그런데 화면이 그 차이를 말하지 않았다.</b> 그래서 초안을 고쳐 시험에 성공한 뒤 확정을
 * 안 하면, 적재는 옛 확정판으로 돌아 <b>「시험은 됐는데 적재만 실패」</b> 가 된다. 사람은
 * 같은 버튼을 같은 화면에서 눌렀으므로 무엇이 달랐는지 알 길이 없다 — 실제로 그 상태가
 * 만들어져 있었고, 옛 확정판은 자동 생성된 빈 뼈대라 전 행이 실패했다.
 *
 * @param activeVersion 확정판 번호. 확정한 적이 없으면 {@code null}
 * @param latestVersion 최신 판 번호. 저장한 적이 없으면 {@code null}
 * @param latestHasFields 최신 판에 이어 둔 칸이 있는가 — <b>사람이 손댔는지</b>를 가른다
 */
public record MappingStateView(Integer activeVersion, Integer latestVersion,
                               boolean latestHasFields) {

    /**
     * 시험과 적재가 서로 <b>다른 판</b>으로 돈다 — 그리고 그 최신 판을 <b>사람이 만들었다.</b>
     *
     * <p>「사람이 만들었는가」 를 함께 보는 이유가 중요하다. 「다시 받아오기」 는 저장된 초안이
     * 없으면 <b>칸이 하나도 없는 빈 초안</b>을 새로 만든다. 그래서 확정 직후에 칸 구조만
     * 갱신해도 판 번호는 갈리는데, 그건 사람이 무엇을 고친 것이 아니다.
     *
     * <p>그 상태에서 「고친 것으로 시험하고 옛것으로 적재합니다」 라고 말하면 <b>사실과
     * 반대</b>다 — 실제로는 시험이 빈 판으로 돌아 실패하고 적재는 멀쩡하다. 더 나쁜 것은 그
     * 문구가 권하는 「연결 확정」 을 누르면 멀쩡한 확정판이 내려가고 빈 판이 올라간다는
     * 점이다. 경고가 사고를 권하는 셈이 된다.
     */
    public boolean drifted() {
        return activeVersion != null && latestVersion != null
                && !activeVersion.equals(latestVersion)
                && latestHasFields;
    }

    /**
     * 칸을 아직 하나도 잇지 않았다.
     *
     * <p>확정한 적이 없어 적재가 막혀 있든, 「다시 받아오기」 가 만든 빈 초안이 최신이든
     * 사람이 할 일은 같다 — <b>칸을 고르고 저장하는 것</b>이다. 두 경우를 갈라 말하면 화면만
     * 복잡해지고 할 일은 달라지지 않는다.
     */
    public boolean needsFields() {
        return latestVersion != null && !latestHasFields;
    }

    /** 저장은 했는데 아직 한 번도 확정하지 않았다 — 적재 자체가 막혀 있다. */
    public boolean neverActivated() {
        return activeVersion == null && latestVersion != null && latestHasFields;
    }
}
