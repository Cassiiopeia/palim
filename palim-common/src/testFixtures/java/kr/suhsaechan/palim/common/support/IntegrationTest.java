package kr.suhsaechan.palim.common.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 실제 PostgreSQL 컨테이너를 띄우는 통합 테스트 기반 클래스.
 *
 * <p>인메모리 데이터베이스를 사용하지 않는다. 비관적 락, 부분 유니크 인덱스,
 * timestamptz 동작이 PostgreSQL과 다르면 검증의 의미가 없기 때문이다.
 *
 * <p>컨테이너는 JVM 당 한 번만 기동하는 singleton 방식이다. {@code @Container}로
 * 클래스 단위 lifecycle을 맡기면, 이 클래스를 상속한 첫 테스트가 끝날 때 컨테이너가
 * 중지되어 이후 테스트가 연결에 실패한다. 종료는 Testcontainers의 Ryuk이 처리한다.
 */
@SpringBootTest
public abstract class IntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
