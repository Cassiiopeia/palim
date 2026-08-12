package kr.suhsaechan.palim.connector.write;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.connector.key.NaturalKeyBuilder;
import kr.suhsaechan.palim.connector.model.FieldDataType;
import kr.suhsaechan.palim.connector.model.TargetField;
import kr.suhsaechan.palim.connector.model.TargetFieldRepository;
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
    private final TargetFieldRepository fieldRepository;

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
        Map<String, FieldDataType> types = fieldTypes(model);
        int written = 0;

        for (MappedRow row : chunk) {
            Map<String, Object> keyParams = naturalKeyParams(row, keyFields, types);
            String naturalKey = keyBuilder.build(row.values(), keyFields);
            saveUndoSnapshot(tenantId, runId, table, keyFields, keyParams, naturalKey);
            upsert(tenantId, runId, table, keyFields, keyParams, row);
            written++;
        }
        return WriteResult.of(written);
    }

    /** 모델의 필드 타입. 자연키 빈 값을 타입에 맞게 채우는 데 쓴다. */
    private Map<String, FieldDataType> fieldTypes(TargetModel model) {
        return fieldRepository.findByTargetModelIdOrderBySortOrder(model.getId()).stream()
                .collect(Collectors.toMap(TargetField::getFieldKey, TargetField::getDataType,
                        (first, second) -> first));
    }

    /**
     * 자연키 바인딩 값.
     *
     * <p>표준 모델의 자연키 컬럼은 전부 {@code NOT NULL} 이다 — 유니크 인덱스에서 NULL 이 서로
     * 다른 값으로 취급되면 창고·로트가 빈 원천이 재실행마다 같은 행을 새로 쌓기 때문이다.
     * 그래서 <b>값이 없으면 타입에 맞는 빈 값으로 바꿔 넘긴다.</b> 그대로 {@code null} 을
     * 넘기면 적재가 제약 위반으로 죽는다.
     *
     * <p>이 정규화는 UPSERT 와 undo 스냅샷 조회가 <b>같은 값</b>을 써야 성립한다. 한쪽만
     * 정규화하면 저장은 빈 문자열로 되고 조회는 {@code null} 로 되어 undo 로그가 조용히 비고,
     * 그 사실은 되돌리기를 눌러본 뒤에야 드러난다.
     */
    private Map<String, Object> naturalKeyParams(MappedRow row, List<String> keyFields,
                                                 Map<String, FieldDataType> types) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (String field : keyFields) {
            Object converted = SqlValues.toParameter(row.values().get(field));
            params.put(field, converted != null ? converted : emptyValue(types.get(field)));
        }
        return params;
    }

    /**
     * 타입별 "값 없음" 표현.
     *
     * <p>날짜·시각은 채우지 않는다. 없는 시점을 지어내면 그 행이 언제 것인지 영영 알 수 없게
     * 되므로, {@code NOT NULL} 제약이 거부하게 두는 편이 낫다. 시점은 자연키의 뼈대라 비어 있는
     * 것 자체가 매핑이 잘못됐다는 신호다.
     */
    private Object emptyValue(FieldDataType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case STRING -> "";
            case INTEGER -> 0;
            case DECIMAL -> BigDecimal.ZERO;
            case BOOLEAN -> false;
            case DATE, TIMESTAMP -> null;
        };
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
                                  Map<String, Object> keyParams, String naturalKey) {
        // 자연키 컬럼이 NOT NULL 이므로 NULL 안전 비교가 필요 없다. 등호를 써야 인덱스를 탄다.
        String keyCondition = keyFields.stream()
                .map(field -> "t." + field + " = :" + field)
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

        for (Map.Entry<String, Object> entry : keyParams.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        spec.update();
    }

    private void upsert(UUID tenantId, UUID runId, String table, List<String> keyFields,
                        Map<String, Object> keyParams, MappedRow row) {
        // 자연키는 정규화된 값으로 덮어쓴다. 매핑되지 않은 자연키 컬럼도 여기서 채워지므로
        // INSERT 에 빠져 DB 기본값에 맡기는 경로가 없어진다 — undo 조회 값과 어긋나지 않는다.
        Map<String, Object> values = new LinkedHashMap<>(row.values());
        values.putAll(keyParams);
        List<String> valueColumns = List.copyOf(values.keySet());

        var spec = jdbcClient.sql(upsertSql(table, valueColumns, keyFields))
                .param("id", UuidV7.generate())
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("attributes", SqlValues.toJson(row.attributes()));

        for (Map.Entry<String, Object> entry : values.entrySet()) {
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
