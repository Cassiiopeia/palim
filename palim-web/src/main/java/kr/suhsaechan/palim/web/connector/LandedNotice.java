package kr.suhsaechan.palim.web.connector;

/**
 * 담은 건수와 <b>지금 남아 있는 건수</b>가 다를 때 그 사정을 말한다.
 *
 * <p>표준 모델은 자리마다 「마지막으로 담은 실행」 하나만 기억한다. 같은 자리를 다시 담으면
 * 그 자리의 주인이 새 실행으로 넘어가므로, 지난 실행의 상세를 열면 담았던 45 줄 중 12 줄만
 * 남아 있을 수 있다.
 *
 * <p>이 차이를 화면이 말하지 않으면 사람은 <b>적재가 깨진 줄 안다.</b> 「45건 성공」 이라고
 * 적힌 바로 아래에 12 줄짜리 표가 있으면 둘 중 하나는 거짓이고, 어느 쪽인지 알 방법이 없다.
 *
 * @param text 화면에 그대로 쓰는 문장
 * @param warn 사람이 손을 봐야 하는 일인가. 되돌린 실행만 해당한다 — 덮어쓰기는 정상이다
 */
public record LandedNotice(String text, boolean warn) {

    /**
     * 알릴 것이 있으면 만들고, 없으면 {@code null}.
     *
     * @param landed     지금 이 실행 것으로 남아 있는 건수
     * @param success    그때 담은 건수
     * @param rolledBack 되돌린 실행인가
     */
    public static LandedNotice of(int landed, int success, boolean rolledBack) {
        if (rolledBack) {
            return new LandedNotice(
                    "이 실행은 되돌렸습니다. 담았던 값은 담기 직전 값으로 돌아갔습니다.", true);
        }
        if (success <= 0 || landed >= success) {
            return null;
        }
        if (landed == 0) {
            return new LandedNotice(
                    "이때 담은 %d건은 모두 이후 실행이 같은 자리에 다시 담았습니다. 지금 그 자리에 있는 것은 나중 값입니다."
                            .formatted(success), false);
        }
        return new LandedNotice(
                "이때 담은 %d건 가운데 %d건이 아직 이 실행 것으로 남아 있습니다. 나머지 %d건은 이후 실행이 다시 담았습니다."
                        .formatted(success, landed, success - landed), false);
    }
}
