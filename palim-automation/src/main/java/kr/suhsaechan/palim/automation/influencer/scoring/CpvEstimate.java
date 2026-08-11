package kr.suhsaechan.palim.automation.influencer.scoring;

/**
 * 추정 단가·CPV. 업계 관행 추정치라 점수에 섞지 않고 별도 컬럼으로만 노출한다(스펙 §5).
 * 실제 견적을 받으면 화면에서 이 값을 덮어쓴다.
 */
public record CpvEstimate(long estimatedPrice, double estimatedCpv) {
}
