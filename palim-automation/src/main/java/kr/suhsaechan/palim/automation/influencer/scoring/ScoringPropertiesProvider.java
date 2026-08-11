package kr.suhsaechan.palim.automation.influencer.scoring;

import kr.suhsaechan.palim.common.config.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 현재 유효한 스코어링 설정 공급.
 *
 * <p>부팅 시점에 한 번 바인딩하는 {@code @ConfigurationProperties} 로는 화면에서 가중치를 바꿔도
 * 재기동 전까지 반영되지 않는다. 그래서 채점할 때마다 여기서 조립한다 — 읽기는 캐시라 저렴하고,
 * 값이 바뀌면 그 다음 채점부터 곧바로 새 기준이 적용된다.
 */
@Component
@RequiredArgsConstructor
public class ScoringPropertiesProvider {

    private final ConfigReader configReader;

    public ScoringProperties current() {
        return ScoringPropertiesAssembler.assemble(configReader);
    }

    /** 점수 행에 남길 기준 버전. */
    public String rubricVersion() {
        return configReader.getString(ScoringConfigKeys.RUBRIC_VERSION);
    }
}
