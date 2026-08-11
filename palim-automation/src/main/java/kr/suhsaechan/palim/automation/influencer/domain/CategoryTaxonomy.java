package kr.suhsaechan.palim.automation.influencer.domain;

/**
 * 분류체계.
 *
 * <p>유튜브 기본 카테고리는 15종뿐이고 「인물/블로그」 같은 덩어리에 절반이 몰려서 광고
 * 집행에는 쓸 수 없다. 그래서 자체 분류를 그 위에 얹되, 원본도 함께 보관한다 — 나중에
 * 분류기를 개선할 때 재분류의 기준선이 되고 발굴 순회에도 그대로 쓰인다.
 */
public enum CategoryTaxonomy {

    /** YouTube videoCategoryId 원본. */
    YOUTUBE,

    /** 발주사와 합의한 자체 카테고리(뷰티·육아·캠핑 등). */
    PALIM
}
