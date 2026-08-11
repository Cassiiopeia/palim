package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.Map;
import java.util.Set;

/** 룰 70점 산출 결과. breakdown 은 화면의 점수 분해 표시와 캘리브레이션 대조에 쓴다. */
public record RuleScore(double total, Map<String, Double> breakdown, Set<Badge> badges) {
}
