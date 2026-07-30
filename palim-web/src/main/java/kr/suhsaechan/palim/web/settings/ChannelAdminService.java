package kr.suhsaechan.palim.web.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.audit.AuditType;
import kr.suhsaechan.palim.channel.Channel;
import kr.suhsaechan.palim.channel.ChannelCredentialService;
import kr.suhsaechan.palim.channel.ChannelService;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널 설정 화면용 조율 서비스.
 *
 * <p>도메인 서비스의 변경 메서드는 {@code Propagation.MANDATORY} 이므로 트랜잭션을 여는 계층이
 * 필요하다. 컨트롤러에 {@code @Transactional} 을 붙이지 않는 이유는, 컨트롤러가 트랜잭션 경계가
 * 되면 뷰 렌더링까지 트랜잭션 안에서 일어나 커넥션을 불필요하게 오래 점유하기 때문이다.
 *
 * <h2>인증정보 값은 절대 반환하지 않는다</h2>
 *
 * <p>AES-GCM 으로 암호화해 저장하는 이유가 유출 방지인데, 화면에서 평문을 되돌려 보여주면
 * 그 노력이 무의미해진다. <b>등록된 키 이름만</b> 반환하고 값은 조회 경로를 두지 않는다.
 * 갱신은 새 값을 입력받는 방식으로만 지원한다(06-OPERATIONS).
 */
@Service
@RequiredArgsConstructor
public class ChannelAdminService {

    /** 채널별로 등록해야 하는 인증정보 키. 화면이 입력란을 만들 때 쓴다. */
    private static final Map<ChannelCode, List<String>> REQUIRED_CREDENTIAL_KEYS =
            new java.util.EnumMap<>(Map.of(
                    ChannelCode.COUPANG, List.of("accessKey", "secretKey", "vendorId"),
                    ChannelCode.NAVER, List.of("clientId", "clientSecret"),
                    ChannelCode.LOTTEON, List.of("authKey"),
                    ChannelCode.ELEVENST, List.of("apiKey"),
                    ChannelCode.ESM, List.of("clientId", "clientSecret"),
                    ChannelCode.SSG, List.of("apiKey"),
                    ChannelCode.LOTTE_DEPT, List.of()));

    private static final String TARGET_TYPE = "CHANNEL";

    private final ChannelService channelService;
    private final ChannelCredentialService channelCredentialService;
    private final WebAuditRecorder webAuditRecorder;

    @Transactional(readOnly = true)
    public List<ChannelSettingView> findAll() {
        return channelService.findAll().stream()
                .sorted(java.util.Comparator.comparing(channel -> channel.getCode().ordinal()))
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ChannelSettingView find(ChannelCode code) {
        return toView(channelService.getByCode(code));
    }

    /**
     * 인증정보를 등록하거나 갱신한다.
     *
     * <p>빈 값은 건너뛴다. 발주자가 일부 키만 갱신할 때 나머지를 지우지 않기 위함이다 —
     * 화면에 기존 값이 표시되지 않으므로 빈 입력란이 "지우기"로 해석되면 안 된다.
     *
     * <p>감사 로그에는 <b>바뀐 키 이름만</b> 남긴다. 값은 스냅샷에 넣지 않는다 — 넣더라도
     * {@code AuditSnapshots} 가 키 이름 기준으로 마스킹하지만, 애초에 전달하지 않는 것이 경계다.
     */
    @Transactional
    public void updateCredentials(ChannelCode code, Map<String, String> plainValues) {
        List<String> updatedKeys = plainValues.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .map(Map.Entry::getKey)
                .toList();

        updatedKeys.forEach(key ->
                channelCredentialService.put(code, key, plainValues.get(key).trim()));

        if (!updatedKeys.isEmpty()) {
            webAuditRecorder.recordChange(AuditType.CHANNEL_CREDENTIAL_UPDATE,
                    TARGET_TYPE, code.name(),
                    "%s 인증정보를 변경했습니다. (키: %s)".formatted(
                            code.displayName(), String.join(", ", updatedKeys)),
                    null,
                    Map.of("updatedKeys", String.join(", ", updatedKeys)));
        }
    }

    @Transactional
    public void enable(ChannelCode code) {
        channelService.enable(code);
        recordToggle(code, true);
    }

    @Transactional
    public void disable(ChannelCode code) {
        channelService.disable(code);
        recordToggle(code, false);
    }

    @Transactional
    public void changeCollectInterval(ChannelCode code, int seconds) {
        int before = channelService.getByCode(code).getCollectIntervalSeconds();
        channelService.changeCollectInterval(code, seconds);

        webAuditRecorder.recordChange(AuditType.CHANNEL_TOGGLE, TARGET_TYPE, code.name(),
                "%s 수집 주기를 변경했습니다.".formatted(code.displayName()),
                Map.of("collectIntervalSeconds", before),
                Map.of("collectIntervalSeconds", seconds));
    }

    private void recordToggle(ChannelCode code, boolean enabled) {
        webAuditRecorder.recordChange(AuditType.CHANNEL_TOGGLE, TARGET_TYPE, code.name(),
                "%s 수집을 %s했습니다.".formatted(code.displayName(), enabled ? "활성화" : "비활성화"),
                Map.of("enabled", !enabled),
                Map.of("enabled", enabled));
    }

    private ChannelSettingView toView(Channel channel) {
        List<String> required = REQUIRED_CREDENTIAL_KEYS.getOrDefault(channel.getCode(), List.of());
        List<String> registered = channelCredentialService.findKeys(channel.getCode());

        // 키별 등록 여부. 값은 담지 않는다.
        Map<String, Boolean> credentialStatus = new LinkedHashMap<>();
        required.forEach(key -> credentialStatus.put(key, registered.contains(key)));

        return new ChannelSettingView(
                channel.getCode(),
                channel.getCode().displayName(),
                channel.isEnabled(),
                channel.getCollectIntervalSeconds(),
                channel.getCollectedUntil(),
                channel.getLastCollectedAt(),
                channel.getLastCollectStatus() != null
                        ? channel.getLastCollectStatus().name() : null,
                channel.getLastCollectError(),
                channel.getConsecutiveFailureCount(),
                credentialStatus,
                required.isEmpty(),
                !credentialStatus.isEmpty() && credentialStatus.values().stream().allMatch(Boolean::booleanValue));
    }
}
