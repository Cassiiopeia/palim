package kr.suhsaechan.palim.common.tenant;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code JdbcClient} 조회에 테넌트 조건을 강제한다.
 *
 * <p>화면용 read model 은 JPA 를 거치지 않으므로 Hibernate 필터가 걸리지 않는다. 각 쿼리가
 * 알아서 조건을 넣게 두면 <b>반드시 빠뜨리는 곳이 생기고, 그것이 곧 데이터 유출이다.</b>
 * 파라미터를 여기서 채워 넣어 빠뜨릴 수 없게 만든다.
 *
 * <p>SQL 에는 {@code :tenantId} 자리만 두면 된다. 값은 이 헬퍼가 현재 테넌트로 채운다.
 */
public final class TenantScopedRepository {

    private TenantScopedRepository() {
    }

    /**
     * 현재 테넌트로 {@code :tenantId} 를 채운 조회 스펙.
     *
     * @param sql {@code :tenantId} 를 포함해야 한다
     */
    public static JdbcClient.StatementSpec scoped(JdbcClient jdbcClient, String sql) {
        if (!sql.contains(":tenantId")) {
            // 조건을 깜빡한 쿼리를 조용히 통과시키면 다른 테넌트의 데이터가 섞인다.
            throw new IllegalArgumentException(
                    "테넌트 조건(:tenantId)이 없는 쿼리는 사용할 수 없습니다");
        }
        return jdbcClient.sql(sql).param("tenantId", TenantContext.current());
    }

    /** 명시적으로 다른 테넌트를 조회할 때. 배치·관리 도구에서만 쓴다. */
    public static JdbcClient.StatementSpec scopedTo(JdbcClient jdbcClient, String sql,
                                                    UUID tenantId) {
        return jdbcClient.sql(sql).param("tenantId", tenantId);
    }
}
