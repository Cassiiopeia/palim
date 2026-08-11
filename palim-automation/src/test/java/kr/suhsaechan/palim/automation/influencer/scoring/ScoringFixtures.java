package kr.suhsaechan.palim.automation.influencer.scoring;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** 스코어링 테스트 공용 픽스처 — 실제 배포 YAML 초기값을 그대로 바인딩해 쓴다. */
final class ScoringFixtures {

    private ScoringFixtures() {
    }

    static ScoringProperties defaultProps() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("influencer-scoring.yml"));
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new PropertiesPropertySource("scoring", yaml.getObject()));
        return new Binder(ConfigurationPropertySources.get(env))
                .bind("palim.influencer.scoring", ScoringProperties.class)
                .get();
    }
}
