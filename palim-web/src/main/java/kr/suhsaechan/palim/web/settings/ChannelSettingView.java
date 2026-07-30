package kr.suhsaechan.palim.web.settings;

import java.time.Instant;
import java.util.Map;
import kr.suhsaechan.palim.common.ChannelCode;

/**
 * 채널 설정 화면 표시용.
 *
 * <p><b>인증정보 값을 담지 않는다.</b> {@code credentialStatus} 는 키 이름과 등록 여부만 갖는다 —
 * 암호화해 저장하는 이유가 유출 방지인데 화면에서 평문을 되돌려 보여주면 무의미해진다.
 *
 * @param code                채널 코드
 * @param displayName         표시명
 * @param enabled             수집 활성 여부
 * @param collectIntervalSeconds 수집 주기(초)
 * @param collectedUntil      수집 커서
 * @param lastCollectedAt     마지막 수집 시각
 * @param lastCollectStatus   마지막 수집 결과
 * @param lastCollectError    마지막 오류
 * @param consecutiveFailureCount 연속 실패 횟수
 * @param credentialStatus    키 이름 → 등록 여부. <b>값은 없다</b>
 * @param credentialNotRequired 인증정보가 필요 없는 채널인지 (엑셀 업로드 채널)
 * @param credentialComplete  필요한 키가 모두 등록되었는지
 */
public record ChannelSettingView(
        ChannelCode code,
        String displayName,
        boolean enabled,
        int collectIntervalSeconds,
        Instant collectedUntil,
        Instant lastCollectedAt,
        String lastCollectStatus,
        String lastCollectError,
        int consecutiveFailureCount,
        Map<String, Boolean> credentialStatus,
        boolean credentialNotRequired,
        boolean credentialComplete
) {

    /** 수집 주기를 분 단위로. 화면 입력은 분이 자연스럽다. */
    public int collectIntervalMinutes() {
        return collectIntervalSeconds / 60;
    }

    /**
     * 활성화할 수 있는지.
     *
     * <p>인증정보가 없는 채널을 활성화하면 인증 실패가 반복되고, 쿠팡은 그것이 지속되면
     * <b>영구 차단</b>된다. 화면에서 미리 막는다.
     */
    public boolean canEnable() {
        return credentialComplete && !credentialNotRequired;
    }

    public boolean hasFailure() {
        return "FAILED".equals(lastCollectStatus);
    }
}
