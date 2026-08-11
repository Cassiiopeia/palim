package kr.suhsaechan.palim.automation.influencer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import kr.suhsaechan.palim.automation.influencer.scoring.CampaignTarget;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 광고 캠페인 브리프.
 *
 * <p>점수는 <b>항상 캠페인 기준</b>이다. 기준 없이 매기는 "좋은 채널" 점수는 광고 집행에
 * 쓸모가 없다 — 같은 채널이 캠페인 A 에서 82점, B 에서 51점이 되는 것이 정상이다.
 *
 * <p>브리프 본문에는 발주사 식별정보가 들어갈 수 있으므로 이 데이터는 DB 에만 존재한다.
 * 코드·문서·테스트에는 합성 값만 쓴다.
 */
@Getter
@Entity
@Table(name = "campaign")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Campaign extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 100)
    private String productCategory;

    @Column(length = 500)
    private String targetAudience;

    /** 제품 소구 포인트. AI 적합도 심사의 판단 근거로 그대로 전달된다. */
    @Column(columnDefinition = "text")
    private String sellingPoints;

    /** 금지 조건(경쟁사 광고 이력, 특정 표현 등). AI 브랜드 안전성 심사에 함께 전달된다. */
    @Column(columnDefinition = "text")
    private String exclusions;

    /** 목표 도달 구간(롱폼 조회수 중앙값 기준). 실도달량 14점의 만점 구간이다. */
    @Column(nullable = false)
    private long targetReachMin;

    @Column(nullable = false)
    private long targetReachMax;

    /** 구독자 하한은 하드 탈락 기준이고, 상한은 예산 상 검토 범위다. */
    @Column(nullable = false)
    private long subscriberMin;

    @Column(nullable = false)
    private long subscriberMax;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CampaignStatus status;

    @Version
    private Long version;

    private Campaign(String name, String productCategory, String targetAudience,
                     String sellingPoints, String exclusions, long targetReachMin,
                     long targetReachMax, long subscriberMin, long subscriberMax) {
        this.id = UuidV7.generate();
        this.name = name;
        this.productCategory = productCategory;
        this.targetAudience = targetAudience;
        this.sellingPoints = sellingPoints;
        this.exclusions = exclusions;
        this.targetReachMin = targetReachMin;
        this.targetReachMax = targetReachMax;
        this.subscriberMin = subscriberMin;
        this.subscriberMax = subscriberMax;
        this.status = CampaignStatus.DRAFT;
    }

    public static Campaign of(String name, String productCategory, String targetAudience,
                              String sellingPoints, String exclusions, long targetReachMin,
                              long targetReachMax, long subscriberMin, long subscriberMax) {
        return new Campaign(name, productCategory, targetAudience, sellingPoints, exclusions,
                targetReachMin, targetReachMax, subscriberMin, subscriberMax);
    }

    /** 스코어링 입력으로 변환 — 도메인과 계산 엔진의 경계다. */
    public CampaignTarget toTarget() {
        return new CampaignTarget(targetReachMin, targetReachMax, subscriberMin, subscriberMax);
    }

    public void activate() {
        this.status = CampaignStatus.ACTIVE;
    }

    public void close() {
        this.status = CampaignStatus.CLOSED;
    }
}
