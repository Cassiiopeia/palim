package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GradeTest {

    @Test
    void 등급_경계값이_스펙과_일치한다() {
        var props = ScoringFixtures.defaultProps().grade();
        assertThat(Grade.of(85.0, props)).isEqualTo(Grade.S);
        assertThat(Grade.of(84.9, props)).isEqualTo(Grade.A);
        assertThat(Grade.of(70.0, props)).isEqualTo(Grade.A);
        assertThat(Grade.of(55.0, props)).isEqualTo(Grade.B);
        assertThat(Grade.of(40.0, props)).isEqualTo(Grade.C);
        assertThat(Grade.of(39.9, props)).isEqualTo(Grade.D);
    }
}
