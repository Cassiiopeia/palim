package kr.suhsaechan.palim.automation.influencer.scoring;

import kr.suhsaechan.palim.common.config.InMemoryConfigReader;

/**
 * 스코어링 테스트 공용 픽스처.
 *
 * <p>기본값은 {@link ScoringConfigDefinitions} 하나에서만 온다. 테스트가 별도 기본값을 들고 있으면
 * 운영에서 쓰는 값과 조용히 어긋나고, 그때 테스트는 통과하는데 현실은 다르게 동작한다.
 */
final class ScoringFixtures {

    private ScoringFixtures() {
    }

    static ScoringProperties defaultProps() {
        return ScoringPropertiesAssembler.assemble(reader());
    }

    static InMemoryConfigReader reader() {
        return InMemoryConfigReader.ofDefaults(new ScoringConfigDefinitions().definitions());
    }
}
