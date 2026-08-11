package kr.suhsaechan.palim.automation.influencer.youtube;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 하루치 API 소모량. */
@Getter
@Entity
@Table(name = "youtube_quota_ledger")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class YoutubeQuotaLedger extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private LocalDate usageDate;

    @Column(nullable = false)
    private int unitsUsed;

    /** 검색 전용 소모량. 전체 예산과 별도로 상한을 둔다. */
    @Column(nullable = false)
    private int searchUnits;

    private YoutubeQuotaLedger(LocalDate usageDate) {
        this.id = UuidV7.generate();
        this.usageDate = usageDate;
    }

    public static YoutubeQuotaLedger startOf(LocalDate usageDate) {
        return new YoutubeQuotaLedger(usageDate);
    }

    public void consume(int units, boolean search) {
        this.unitsUsed += units;
        if (search) {
            this.searchUnits += units;
        }
    }
}
