package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.Map;

/**
 * 라이징 지수 산출 결과. 캠페인과 무관한 전 풀 스캔용이다(스펙 §7).
 *
 * <p>{@code risingBadge} 가 참이면 매일 스냅샷 대상으로 승격되고 주간 텔레그램 알림에 실린다.
 */
public record RisingIndex(double total, Map<String, Double> breakdown, boolean risingBadge) {
}
