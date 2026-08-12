package kr.suhsaechan.palim.connector.write;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.connector.key.NaturalKeyBuilder;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.transform.MappedRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * LIVE 실행 적재기 — 표준 테이블에 UPSERT.
 *
 * <p>JPA 가 아니라 {@link JdbcClient} 를 쓴다. 목표 테이블이 <b>실행 시점에 결정</b>되므로
 * 엔티티 타입을 컴파일 시점에 고정할 수 없고, {@code ON CONFLICT} 는 JPA 로 표현되지 않는다.
 *
 * <p>UPSERT 직전 값을 {@code connector_undo_log} 에 남긴다. 되돌리기는 <b>가장 최근 실행
 * 하나</b>만 허용한다 — 그 이전까지 거슬러 오르면 이후 실행들과 뒤엉켜 어떤 상태로 돌아가는지
 * 아무도 설명할 수 없다.
 */
@Component
@RequiredArgsConstructor
public class StandardModelWriter implements RecordWriter {

    private final JdbcClient jdbcClient;
    private final NaturalKeyBuilder keyBuilder;

    @Override
    public RunMode mode() {
        return RunMode.LIVE;
    }

    @Override
    @Transactional
    public WriteResult write(UUID tenantId, UUID runId, TargetModel model,
                             List<MappedRow> chunk) {
        String table = model.getTableName();
        List<String> keyFields = model.getNaturalKeyFields();
        int written = 0;

        for (MappedRow row : chunk) {
            String naturalKey = keyBuilder.build(row.values(), keyFields);
            saveUndoSnapshot(tenantId, runId, table, keyFields, naturalKey, row);
            upsert(tenantId, runId, table, keyFields, row);
            written++;
        }
        return WriteResult.of(written);
    }

    /**
     * UPSERT 직전 값 보관.
     *
     * <p>대상 행이 없으면 아무것도 삽입되지 않고, 되돌리기는 "이번 실행이 만든 행 삭제"가 된다.
     *
     * <p>{@code ON CONFLICT DO NOTHING} 이 중요하다. 원천 파일에 같은 자연키가 두 번 나오는 것은
     * 흔한 일인데, 그대로 두면 두 번째 undo 행의 이전 값이 <b>이번 실행이 방금 쓴 값</b>이 되어
     * 복원이 최초 상태가 아니라 중간 상태로 간다.
     */
    private void saveUndoSnapshot(UUID tenantId, UUID runId, String table, List<String> keyFields,
                                  String naturalKey, MappedRow row) {
        String keyCondition = keyFields.stream()
                .map(field -> "t." + field + " IS NOT DISTINCT FROM :" + field)
                .reduce((a, b) -> a + " AND " + b)
                .orElseThrow();

        var spec = jdbcClient.sql("""
                        INSERT INTO connector_undo_log
                            (id, tenant_id, run_id, table_name, natural_key, previous_row,
                             created_at, updated_at)
                        SELECT :undoId, :tenantId, :runId, :tableName, :naturalKey,
                               to_jsonb(t), now(), now()
                        FROM %s t
                        WHERE t.tenant_id = :tenantId AND %s
                        ON CONFLICT (run_id, table_name, natural_key) DO NOTHING
                        """.formatted(table, keyCondition))
                .param("undoId", UuidV7.generate())
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("tableName", table)
                .param("naturalKey", naturalKey);

        for (String field : keyFields) {
            spec = spec.param(field, SqlValues.toParameter(row.values().get(field)));
        }
        spec.update();
    }

    private void upsert(UUID tenantId, UUID runId, String table, List<String> keyFields,
                        MappedRow row) {
        List<String> valueColumns = List.copyOf(row.values().keySet());

        var spec = jdbcClient.sql(upsertSql(table, valueColumns, keyFields))
                .param("id", UuidV7.generate())
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("attributes", SqlValues.toJson(row.attributes()));

        for (Map.Entry<String, Object> entry : row.values().entrySet()) {
            spec = spec.param(entry.getKey(), SqlValues.toParameter(entry.getValue()));
        }
        spec.update();
    }

    /**
     * UPSERT SQL 생성.
     *
     * <p>{@code ON CONFLICT} 의 컬럼 목록이 유니크 인덱스와 <b>정확히</b> 일치해야 한다.
     * 하나라도 다르면 PostgreSQL 이 "no unique or exclusion constraint matching" 으로 거부한다.
     *
     * <p>자연키 컬럼은 UPDATE 대상에서 뺀다. 어차피 같은 값이고, 넣으면 무엇이 갱신 대상인지
     * 읽기 어려워진다.
     */
    private String upsertSql(String table, List<String> valueColumns, List<String> keyFields) {
        Map<String, String> insertColumns = new LinkedHashMap<>();
        insertColumns.put("id", ":id");
        insertColumns.put("tenant_id", ":tenantId");
        insertColumns.put("run_id", ":runId");
        valueColumns.forEach(column -> insertColumns.put(column, ":" + column));
        insertColumns.put("attributes", "cast(:attributes as jsonb)");

        String columnList = String.join(", ", insertColumns.keySet());
        String valueList = String.join(", ", insertColumns.values());

        String updates = valueColumns.stream()
                .filter(column -> !keyFields.contains(column))
                .map(column -> column + " = EXCLUDED." + column)
                .reduce((a, b) -> a + ", " + b)
                .map(joined -> joined + ", ")
                .orElse("")
                + "run_id = EXCLUDED.run_id, attributes = EXCLUDED.attributes, updated_at = now()";

        String conflict = "tenant_id, " + String.join(", ", keyFields);

        return """
                INSERT INTO %s (%s, created_at, updated_at)
                VALUES (%s, now(), now())
                ON CONFLICT (%s) DO UPDATE SET %s
                """.formatted(table, columnList, valueList, conflict, updates);
    }
}
