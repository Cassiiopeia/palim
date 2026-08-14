package kr.suhsaechan.palim.web.connector;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 화면용 조회.
 *
 * <p>커넥터·목표 모델·매핑·최근 실행이 한 화면에 함께 필요하다. JPA 로 각각 조회하면 목록
 * 한 번에 N+1 이 나므로 {@code JdbcClient} 로 read model 을 만든다(02-ARCHITECTURE 의존 규칙).
 */
@Service
@RequiredArgsConstructor
public class ConnectorQueryService {

    private final JdbcClient jdbcClient;

    /**
     * 커넥터 목록.
     *
     * <p>마지막 실행은 {@code LATERAL} 로 커넥터당 1건만 가져온다. 실행 이력 전체를 조인해
     * 집계하면 이력이 쌓일수록 목록이 느려진다.
     */
    @Transactional(readOnly = true)
    public List<ConnectorSummary> list(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT c.id, c.code, c.name, m.name AS target_model_name,
                               c.source_type, c.enabled,
                               am.version AS active_version,
                               r.status AS last_status, r.started_at AS last_run_at,
                               coalesce(r.success_count, 0)::int AS last_success,
                               coalesce(r.failed_count, 0)::int AS last_failed,
                               c.connection_status, c.schedule_cron
                        FROM connector c
                        JOIN target_model m ON m.id = c.target_model_id
                        LEFT JOIN connector_mapping am
                               ON am.connector_id = c.id AND am.status = 'ACTIVE'
                        LEFT JOIN LATERAL (
                            SELECT status, started_at, success_count, failed_count
                            FROM connector_run
                            WHERE connector_id = c.id
                            ORDER BY started_at DESC
                            LIMIT 1
                        ) r ON true
                        WHERE c.tenant_id = :tenantId
                        ORDER BY c.name
                        """)
                .param("tenantId", tenantId)
                .query(ConnectorSummary.class)
                .list();
    }

    /** 실행 이력. 대량 실패에 대비해 건수를 제한한다. */
    @Transactional(readOnly = true)
    public List<RunSummary> runs(UUID connectorId, int limit) {
        return jdbcClient.sql("""
                        SELECT id, run_mode, trigger_type, status, mapping_version,
                               total_count, success_count, failed_count,
                               started_at, finished_at, error_summary
                        FROM connector_run
                        WHERE connector_id = :connectorId
                        ORDER BY started_at DESC
                        LIMIT :limit
                        """)
                .param("connectorId", connectorId)
                .param("limit", limit)
                .query(RunSummary.class)
                .list();
    }

    /**
     * 실패 행.
     *
     * <p><b>반드시 제한한다.</b> 대량 원천에서 실패가 수만 건이면 무제한 조회가 힙을 채운다.
     */
    @Transactional(readOnly = true)
    public List<RunErrorRow> errors(UUID runId, int limit) {
        return jdbcClient.sql("""
                        SELECT row_number, error_code, message, source_row::text AS source_row
                        FROM connector_run_error
                        WHERE run_id = :runId
                        ORDER BY row_number
                        LIMIT :limit
                        """)
                .param("runId", runId)
                .param("limit", limit)
                .query(RunErrorRow.class)
                .list();
    }

    /** 테스트 적재 결과 미리보기. */
    @Transactional(readOnly = true)
    public List<StagingRow> staging(UUID runId, int limit) {
        return jdbcClient.sql("""
                        SELECT row_number, natural_key, payload::text AS payload
                        FROM connector_staging
                        WHERE run_id = :runId
                        ORDER BY row_number
                        LIMIT :limit
                        """)
                .param("runId", runId)
                .param("limit", limit)
                .query(StagingRow.class)
                .list();
    }
}
