package kr.suhsaechan.palim.connector.source.http;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 프리셋 → 검증기.
 *
 * <p>새 인증 흐름은 {@link ApiProbe} 구현체를 빈으로 등록하는 것만으로 붙는다. 화면과 저장
 * 구조는 손대지 않는다.
 */
@Component
public class ApiProbeRegistry {

    private final Map<ApiAuthPreset.AuthFlow, ApiProbe> probes = new EnumMap<>(ApiAuthPreset.AuthFlow.class);

    public ApiProbeRegistry(List<ApiProbe> apiProbes) {
        apiProbes.forEach(probe -> probes.put(probe.flow(), probe));
    }

    public ApiProbe of(ApiAuthPreset preset) {
        ApiProbe probe = probes.get(preset.getFlow());
        if (probe == null) {
            throw new BusinessException(ErrorCode.API_PROBE_INCOMPLETE, preset.name());
        }
        return probe;
    }
}
