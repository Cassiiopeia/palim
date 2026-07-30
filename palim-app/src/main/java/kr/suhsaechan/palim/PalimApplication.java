package kr.suhsaechan.palim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 진입점.
 *
 * <p>패키지가 {@code kr.suhsaechan.palim}이라 하위 모듈의 컴포넌트가 모두 스캔된다.
 * {@code @EnableJpaAuditing}이 없으면 {@code BaseTimeEntity}의 시각 필드가 전부 null로 저장된다.
 */
@EnableScheduling
@EnableJpaAuditing
@ConfigurationPropertiesScan
@SpringBootApplication
public class PalimApplication {

    public static void main(String[] args) {
        SpringApplication.run(PalimApplication.class, args);
    }
}
