package kr.suhsaechan.palim.automation.influencer.ai;

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

/** 하루치 AI 호출 수. 프로세스 재시작에도 상한이 유지되도록 DB 에 남긴다. */
@Getter
@Entity
@Table(name = "ai_call_ledger")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiCallLedger extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private LocalDate usageDate;

    @Column(nullable = false)
    private int callCount;

    private AiCallLedger(LocalDate usageDate) {
        this.id = UuidV7.generate();
        this.usageDate = usageDate;
    }

    public static AiCallLedger startOf(LocalDate usageDate) {
        return new AiCallLedger(usageDate);
    }

    public void increase() {
        this.callCount++;
    }
}
