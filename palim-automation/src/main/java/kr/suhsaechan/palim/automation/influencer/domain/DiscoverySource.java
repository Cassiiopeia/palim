package kr.suhsaechan.palim.automation.influencer.domain;

/**
 * 채널을 처음 발견한 경로.
 *
 * <p>발굴 품질을 사후 평가하는 근거다 — 어느 경로가 실제 광고 제안까지 이어졌는지 보면
 * 시드 키워드와 quota 예산을 어디에 더 쓸지 판단할 수 있다.
 */
public enum DiscoverySource {

    /** 카테고리 시드 키워드 검색. 중견 채널 발굴의 주력이다(호출당 100 units). */
    KEYWORD_SEARCH,

    /** 국내 인기 차트 누적. 호출당 1 unit 으로 저렴하지만 대형·기업 채널로 치우친다. */
    POPULAR_CHART,

    /** 이미 발견한 채널의 추천 채널 섹션에서 확장. */
    FEATURED_CHANNEL,

    /** 발주사가 엑셀로 올린 수동 시드. */
    MANUAL_SEED
}
