package kr.suhsaechan.palim.web.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.suhsaechan.palim.common.BaseAtGranularity;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 화면용 조회.
 *
 * <p>커넥터·목표 모델·매핑·최근 실행이 한 화면에 함께 필요하다. JPA 로 각각 조회하면 목록
 * 한 번에 N+1 이 나므로 {@code JdbcClient} 로 read model 을 만든다(02-ARCHITECTURE 의존 규칙).
 */
@Service
@RequiredArgsConstructor
public class ConnectorQueryService {

    /**
     * SQL 에 이어 붙여도 되는 이름. 소문자·숫자·밑줄만, 63 자 이하(PostgreSQL 식별자 상한).
     *
     * <p>표 이름과 칸 이름은 바인딩할 수 없어 문자열로 이어 붙일 수밖에 없다. 그 자리에서
     * 걸러 내지 않으면 화면 조회 한 번이 임의 SQL 실행이 된다.
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 표준에 없는 원천 칸이 모이는 자리. 표를 넓히지 않도록 표 밖에 따로 둔다. */
    private static final String ATTRIBUTES = "attributes";

    /**
     * 어느 표에나 있는 살림용 칸. 담긴 값이 아니므로 표에 세우지 않는다.
     *
     * <p>보여 줘 봐야 모든 줄에서 같은 모양이라, 정작 봐야 할 수량·품목을 화면 밖으로 민다.
     */
    private static final Set<String> HOUSEKEEPING =
            Set.of("id", "tenant_id", "run_id", "created_at", "updated_at", ATTRIBUTES);

    private final JdbcClient jdbcClient;

    /**
     * 그 원천을 담는 연동이 <b>어느 눈금으로</b> 기준 시각을 남기는가.
     *
     * <p>대조가 견줄 눈금을 정할 때 이 값보다 잘게 잡으면 두 원천이 같은 칸에 영영 못 들어온다.
     * 연동을 찾지 못하면 <b>하루</b>로 본다 — 연동을 지웠어도 담긴 자료는 견줄 수 있어야 한다.
     */
    @Transactional(readOnly = true)
    public BaseAtGranularity granularityOf(UUID tenantId, String code) {
        return jdbcClient.sql("""
                        SELECT base_at_granularity FROM connector
                        WHERE tenant_id = :tenant AND code = :code
                        """)
                .param("tenant", tenantId)
                .param("code", code)
                .query(String.class)
                .optional()
                .map(BaseAtGranularity::valueOf)
                .orElse(BaseAtGranularity.DAY);
    }

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

    /**
     * 그 실행이 <b>그때</b> 쓰던 칸 연결.
     *
     * <p>지금 활성 매핑이 아니라 {@code connector_run.mapping_id} 를 따라간다. 연결을 고친 뒤에
     * 옛 실행을 열면, 지금 정의로 그린 표가 그때 담긴 값과 어긋나기 때문이다.
     */
    @Transactional(readOnly = true)
    public List<RunFieldMap> runFieldMaps(UUID runId) {
        return jdbcClient.sql("""
                        SELECT fm.source_field, fm.target_field_key, tf.display_name
                        FROM connector_run r
                        JOIN connector c ON c.id = r.connector_id
                        JOIN connector_field_map fm ON fm.mapping_id = r.mapping_id
                        LEFT JOIN target_field tf ON tf.target_model_id = c.target_model_id
                                                 AND tf.field_key = fm.target_field_key
                        WHERE r.id = :runId
                        ORDER BY fm.sort_order, fm.target_field_key
                        """)
                .param("runId", runId)
                .query(RunFieldMap.class)
                .list();
    }

    /**
     * 실제 적재가 <b>진짜로 담아 둔 것</b>.
     *
     * <p>실제 적재는 스테이징을 거치지 않고 표준 모델 표에 바로 쓴다. 그 표로 이어지는 길이
     * 화면에 없어서, 45 건이 멀쩡히 들어간 실행이 「보여줄 내역이 없다」 로 보였다. 담긴 것을
     * 확인할 수 없으면 확인 단계가 형식이 되고, 잘못 담긴 값이 그대로 대조의 기준이 된다.
     *
     * <p>{@code run_id} 로 찾는다. 적재 시 함께 넣고 덮어쓸 때도 갱신하므로, 이 값은 언제나
     * <b>그 자리를 마지막으로 담은 실행</b>을 가리킨다. 그래서 지난 실행의 행은 나중 실행이
     * 같은 자리를 담는 순간 여기서 빠진다 — 없어진 것이 아니라 주인이 바뀐 것이라,
     * {@code total} 과 실행의 성공 건수를 나란히 놓아 화면이 그 사정을 말할 수 있게 한다.
     *
     * <p>연동이 표준 모델을 쓰지 않으면(파일 저장 등) 빈 값을 준다.
     */
    @Transactional(readOnly = true)
    public Optional<LandedResult> landed(UUID runId, List<String> wanted, int limit) {
        Optional<LandingSpot> spot = landingSpot(runId);
        if (spot.isEmpty() || spot.get().tableName() == null) {
            return Optional.empty();
        }
        String table = identifier(spot.get().tableName());

        // 정의에 있는 칸이 표에도 있으리라는 보장은 없다. 없는 칸을 SELECT 에 넣으면 조회가
        // 통째로 죽어 화면이 안 열린다 — 한 칸 때문에 실행 결과 전부를 못 보게 된다.
        List<String> defined = tableColumns(table);
        Set<String> actual = Set.copyOf(defined);
        List<String> order = spot.get().naturalKeyFields().stream().filter(actual::contains)
                .toList();

        int total = jdbcClient.sql("SELECT count(*)::int FROM %s WHERE run_id = :runId"
                        .formatted(table))
                .param("runId", runId)
                .query(Integer.class)
                .single();
        if (total == 0) {
            return Optional.of(new LandedResult(spot.get().modelName(), table, List.of(),
                    List.of(), 0));
        }

        List<String> columns = columns(table, defined, wanted, runId, limit);
        List<LandedRow> rows = columns.isEmpty()
                ? List.of()
                : rows(table, columns, order, actual.contains(ATTRIBUTES), runId, limit);

        return Optional.of(new LandedResult(spot.get().modelName(), table, columns, rows, total));
    }

    /**
     * 표에 세울 칸을 고른다.
     *
     * <p>연결한 칸은 <b>값이 없어도</b> 세운다 — 「연결했는데 안 담겼다」 가 정확히 사람이 봐야
     * 하는 실패이고, 빼 버리면 그 사실을 알 길이 없다.
     *
     * <p>연결에 없는 칸도 값이 있으면 세운다. 단위 환산 결과({@code base_quantity})처럼 연결
     * 없이 채워지는 칸이 있기 때문이다. 오히려 그 칸이 확인해야 할 값일 때가 많다 — 「무엇으로
     * 환산돼 담겼는가」 가 대조의 기준이 되므로.
     *
     * <p>순서는 연결한 칸이 먼저다. 연결 화면에서 위에 있던 칸이 여기서도 왼쪽에 오지 않으면
     * 같은 것을 두 화면에서 두 번 익혀야 한다.
     */
    private List<String> columns(String table, List<String> defined, List<String> wanted,
                                 UUID runId, int limit) {
        Set<String> actual = Set.copyOf(defined);
        List<String> columns = new ArrayList<>(wanted.stream().distinct()
                .filter(actual::contains)
                .filter(column -> !HOUSEKEEPING.contains(column))
                .toList());

        Set<String> valued = valuedColumns(table, runId, limit);
        defined.stream()
                .filter(valued::contains)
                .filter(column -> !columns.contains(column))
                .forEach(columns::add);
        return List.copyOf(columns);
    }

    /**
     * 화면에 그릴 줄에서 <b>값이 하나라도 있는</b> 칸.
     *
     * <p>표 전체가 아니라 그릴 줄만 본다. 그리지 않는 줄의 칸까지 세우면 화면에는 빈 칸만
     * 늘어서고, 표는 옆으로 밀려 정작 봐야 할 값이 화면 밖으로 나간다.
     */
    private Set<String> valuedColumns(String table, UUID runId, int limit) {
        return Set.copyOf(jdbcClient.sql("""
                        SELECT DISTINCT jsonb_object_keys(jsonb_strip_nulls(
                                   to_jsonb(t) - cast(:housekeeping as text[])))
                        FROM (SELECT * FROM %s WHERE run_id = :runId LIMIT :limit) t
                        """.formatted(table))
                .param("housekeeping", "{" + String.join(",", HOUSEKEEPING) + "}")
                .param("runId", runId)
                .param("limit", limit)
                .query(String.class)
                .list());
    }

    private List<LandedRow> rows(String table, List<String> columns, List<String> orderBy,
                                 boolean hasAttributes, UUID runId, int limit) {
        // 순서가 실행마다 달라지면 같은 화면을 두 번 열었을 때 줄이 뒤섞여, 방금 본 줄을 다시
        // 찾지 못한다. 자연키가 곧 「이 줄이 무엇인가」 이므로 그 순서로 세운다.
        String order = orderBy.isEmpty() ? "id" : String.join(", ", orderBy) + ", id";
        String extra = hasAttributes ? ", attributes::text AS attributes_json" : "";

        return jdbcClient.sql("""
                        SELECT %s%s FROM %s WHERE run_id = :runId ORDER BY %s LIMIT :limit
                        """.formatted(String.join(", ", columns), extra, table, order))
                .param("runId", runId)
                .param("limit", limit)
                .query((rs, rowNum) -> {
                    List<String> values = new ArrayList<>(columns.size());
                    for (String column : columns) {
                        Object value = rs.getObject(column);
                        values.add(value == null ? "" : String.valueOf(value));
                    }
                    return new LandedRow(rowNum + 1, values,
                            hasAttributes ? rs.getString("attributes_json") : null);
                })
                .list();
    }

    /** 어느 표에 담는 연동이었나. 대상 모델이 표를 쓰지 않으면 {@code tableName} 이 비어 있다. */
    private Optional<LandingSpot> landingSpot(UUID runId) {
        return jdbcClient.sql("""
                        SELECT m.table_name, m.name AS model_name,
                               m.natural_key_fields::text AS natural_key_fields
                        FROM connector_run r
                        JOIN connector c ON c.id = r.connector_id
                        JOIN target_model m ON m.id = c.target_model_id
                        WHERE r.id = :runId
                        """)
                .param("runId", runId)
                .query((rs, rowNum) -> new LandingSpot(rs.getString("table_name"),
                        rs.getString("model_name"),
                        jsonArray(rs.getString("natural_key_fields"))))
                .optional();
    }

    /** 그 표에 실제로 있는 칸. 표를 만들 때 정한 순서를 그대로 준다. */
    private List<String> tableColumns(String table) {
        return jdbcClient.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = current_schema() AND table_name = :table
                        ORDER BY ordinal_position
                        """)
                .param("table", table)
                .query(String.class)
                .list();
    }

    /** 실행 시점의 칸 연결 한 줄. */
    public record RunFieldMap(String sourceField, String targetFieldKey, String displayName) {

        /** 화면에 쓸 이름. 표준 칸에 사람 이름이 없으면 칸 키를 그대로 쓴다. */
        public String label() {
            return displayName == null || displayName.isBlank() ? targetFieldKey : displayName;
        }
    }

    /**
     * 실제 적재가 담아 둔 것.
     *
     * @param total 지금 이 실행 것으로 남아 있는 건수. 실행의 성공 건수보다 적을 수 있다
     */
    public record LandedResult(String modelName, String tableName, List<String> columns,
                               List<LandedRow> rows, int total) {
    }

    /** 담긴 행 하나. {@code values} 는 {@link LandedResult#columns()} 순서와 맞춰 둔다. */
    public record LandedRow(int rowNumber, List<String> values, String attributes) {
    }

    private record LandingSpot(String tableName, String modelName, List<String> naturalKeyFields) {
    }

    /**
     * SQL 에 이어 붙여도 되는 이름인가.
     *
     * <p>표 이름과 칸 이름은 값이 아니라 <b>식별자</b>라 바인딩할 수 없다 — 문자열로 이어 붙일
     * 수밖에 없다. 부르는 쪽이 DB 에서 읽은 이름만 넘긴다 해도 그 전제가 언제 깨질지는 알 수
     * 없으므로, 이어 붙이기 직전에 여기서 한 번 더 막는다.
     */
    private static String identifier(String name) {
        if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return name;
    }

    /** {@code ["a","b"]} 형태를 목록으로. 읽지 못하면 빈 목록 — 정렬만 {@code id} 로 떨어진다. */
    private static List<String> jsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            node.forEach(element -> values.add(element.asString()));
            return List.copyOf(values);
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
