package kr.suhsaechan.palim.connector.run;

import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.model.TargetModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 되돌리기.
 *
 * <p>TEST 는 스테이징만 지우므로 제한이 없다. LIVE 는 <b>가장 최근 실행 하나</b>만 되돌린다 —
 * 그 이전까지 거슬러 오르면 이후 실행들과 뒤엉켜 어떤 상태로 돌아가는지 아무도 설명할 수 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RollbackService {

    private final ConnectorRunRepository runRepository;
    private final ConnectorLoader loader;
    private final JdbcClient jdbcClient;

    @Transactional
    public ConnectorRun rollback(UUID runId) {
        ConnectorRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTOR_NOT_FOUND));

        if (run.isTest()) {
            jdbcClient.sql("DELETE FROM connector_staging WHERE run_id = :runId")
                    .param("runId", runId).update();
        } else {
            requireLatestLive(run);
            restoreStandardTable(run);
        }

        run.markRolledBack();
        return runRepository.save(run);
    }

    private void requireLatestLive(ConnectorRun run) {
        boolean latest = runRepository
                .findFirstByConnectorIdAndRunModeOrderByStartedAtDesc(
                        run.getConnectorId(), RunMode.LIVE)
                .map(found -> found.getId().equals(run.getId()))
                .orElse(false);

        if (!latest) {
            throw new BusinessException(ErrorCode.ROLLBACK_NOT_ALLOWED);
        }
    }

    /**
     * 표준 테이블 복원.
     *
     * <p>순서가 중요하다.
     *
     * <ol>
     *   <li><b>덮어쓴 행 복원</b> — 기존 행을 지우고 {@code previous_row} 를 통째로 되살린다.
     *       복원된 행의 {@code run_id} 는 이전 실행 값이 된다</li>
     *   <li><b>남은 행 삭제</b> — 아직 이번 {@code run_id} 를 달고 있는 행은 이번 실행이 처음
     *       만든 것이다. 복원된 행은 1단계에서 {@code run_id} 가 바뀌어 여기 걸리지 않는다</li>
     * </ol>
     *
     * <p>순서를 뒤집으면 복원 대상까지 지워진 뒤 되살아나 결과는 같지만, 그 사이 다른 트랜잭션이
     * 읽으면 행이 없는 순간이 보인다.
     */
    private void restoreStandardTable(ConnectorRun run) {
        Connector connector = loader.connector(run.getConnectorId());
        TargetModel model = loader.targetModel(connector);
        String table = model.getTableName();

        if (table == null) {
            // 커스텀 모델은 custom_record 한 테이블이라 run_id 삭제로 끝난다.
            jdbcClient.sql("DELETE FROM custom_record WHERE run_id = :runId")
                    .param("runId", run.getId()).update();
            jdbcClient.sql("DELETE FROM connector_undo_log WHERE run_id = :runId")
                    .param("runId", run.getId()).update();
            return;
        }

        jdbcClient.sql("""
                        DELETE FROM %s
                        WHERE id IN (
                            SELECT (previous_row ->> 'id')::uuid FROM connector_undo_log
                            WHERE run_id = :runId AND table_name = :tableName
                              AND previous_row IS NOT NULL)
                        """.formatted(table))
                .param("runId", run.getId()).param("tableName", table).update();

        int restored = jdbcClient.sql("""
                        INSERT INTO %s
                        SELECT (jsonb_populate_record(null::%s, previous_row)).*
                        FROM connector_undo_log
                        WHERE run_id = :runId AND table_name = :tableName
                          AND previous_row IS NOT NULL
                        """.formatted(table, table))
                .param("runId", run.getId()).param("tableName", table).update();

        int deleted = jdbcClient.sql("DELETE FROM %s WHERE run_id = :runId".formatted(table))
                .param("runId", run.getId()).update();

        jdbcClient.sql("DELETE FROM connector_undo_log WHERE run_id = :runId")
                .param("runId", run.getId()).update();

        log.info("실행 되돌리기 — 복원 {}건, 삭제 {}건 ({})", restored, deleted, table);
    }
}
