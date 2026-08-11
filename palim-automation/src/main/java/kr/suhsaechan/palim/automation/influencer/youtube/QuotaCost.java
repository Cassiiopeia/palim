package kr.suhsaechan.palim.automation.influencer.youtube;

/**
 * YouTube Data API 호출 비용(units).
 *
 * <p>호출마다 비용이 100배까지 차이 난다. {@link #SEARCH} 만 100 이고 나머지는 1 이라, 발굴을
 * 검색에만 의존하면 하루 100회로 끝난다. 인기 차트·추천 채널을 주 엔진으로 쓰는 설계는 이
 * 비대칭에서 나온 것이다.
 *
 * <p>공식 문서 기준 고정값이라 설정으로 빼지 않는다 — 우리가 정하는 값이 아니다.
 */
public final class QuotaCost {

    /** 키워드 검색. 압도적으로 비싸다. */
    public static final int SEARCH = 100;

    /** 채널 통계 조회. 50개까지 한 번에 받는다. */
    public static final int CHANNELS_LIST = 1;

    /** 영상 통계 조회. 50개 배치. 인기 차트도 이 비용이다. */
    public static final int VIDEOS_LIST = 1;

    /** 업로드 재생목록 조회. */
    public static final int PLAYLIST_ITEMS = 1;

    /** 추천 채널 섹션. */
    public static final int CHANNEL_SECTIONS = 1;

    /** 댓글 조회. */
    public static final int COMMENT_THREADS = 1;

    private QuotaCost() {
    }
}
