package kr.suhsaechan.palim.connector.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.transform.MappedRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매핑을 마친 행들에 <b>사장님이 쓴 스크립트를 차례로 태운다.</b>
 *
 * <p>담기 직전에 돈다. 담고 나서 고치면 「담긴 값」과 「보이는 값」이 한동안 달라지고, 그
 * 어긋남은 오류로 남지 않아 찾기 어렵다.
 *
 * <p><b>돌려준 칸만 덮어쓴다.</b> 스크립트가 이름만 다듬어도 나머지 칸이 날아가지 않아야,
 * 사장님이 「이 칸만 건드리는 스크립트」를 마음 놓고 쓸 수 있다.
 *
 * <p>바꿀 수 있는 칸을 <b>제한하지 않는다.</b> 무엇을 잠글지는 코드가 정할 일이 아니다.
 * 안전은 막는 것이 아니라 드러내는 것으로 확보한다 — 스크립트가 필수 칸을 지우면 담기 직전
 * 검사에 걸려 그 줄만 떨어지고 이유가 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostScriptStage {

    /**
     * 어느 행인지 알려주는 손잡이.
     *
     * <p>사장님 자료가 아니라 <b>우리가 붙였다 떼는 값</b>이다. 그대로 돌려주기만 하면 되고,
     * 반영할 때는 다시 뗀다. 이것이 있어야 품목코드까지 바꿀 수 있다.
     */
    public static final String ROW_HANDLE = "_row";

    private final PostScriptRepository scriptRepository;
    private final PostScriptRunRecorder recorder;
    private final PostScriptRunner runner;

    /**
     * @param connectorRunId 어느 적재에 딸려 돌았는지. 기록에 남는다
     * @return 스크립트를 거친 행들. 스크립트가 없거나 전부 꺼져 있으면 받은 그대로
     */
    @Transactional
    public List<MappedRow> apply(UUID tenantId, UUID connectorId, UUID connectorRunId,
                                 List<MappedRow> rows) {
        List<PostScript> scripts = scriptRepository
                .findByConnectorIdAndStatusAndIsEnabledTrueOrderBySortOrder(
                        connectorId, PostScriptStatus.ACTIVE);
        if (scripts.isEmpty() || rows.isEmpty()) {
            return rows;
        }
        log.debug("후처리 시작 — 커넥터={} 스크립트={}개 행={}건",
                connectorId, scripts.size(), rows.size());

        List<MappedRow> current = rows;
        for (PostScript script : scripts) {
            current = applyOne(tenantId, connectorRunId, script, current);
        }
        return current;
    }

    private List<MappedRow> applyOne(UUID tenantId, UUID connectorRunId, PostScript script,
                                     List<MappedRow> rows) {
        List<Map<String, Object>> input = rows.stream().map(row -> {
            Map<String, Object> values = new LinkedHashMap<>(row.values());
            values.put(ROW_HANDLE, row.rowNumber());
            return values;
        }).toList();
        PostScriptResult result = runner.run(script.getBody(), input, script.getTimeoutMs());

        if (!result.isSucceeded()) {
            // 반쯤 적용된 상태로 담으면 어떤 행은 다듬어지고 어떤 행은 아닌 자료가 남는다.
            // 그것이 가장 찾기 어려운 고장이라, 실행 전체를 세운다.
            recorder.recordFailed(tenantId, script, connectorRunId, rows.size(), result);
            log.error("후처리 스크립트 실패로 실행을 세웁니다 — 스크립트={}({}) 사유={}",
                    script.getName(), script.getId(), result.message());
            throw new BusinessException(ErrorCode.HOOK_EXECUTION_FAILED,
                    script.getName(), result.message());
        }

        Map<Integer, Map<String, Object>> byHandle = index(result.rows());
        List<MappedRow> merged = new ArrayList<>(rows.size());
        int changed = 0;
        for (MappedRow row : rows) {
            Map<String, Object> patch = byHandle.get(row.rowNumber());
            if (patch == null || patch.isEmpty()) {
                merged.add(row);
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>(row.values());
            // 돌려준 칸만 덮는다. 안 돌려준 칸은 매핑 결과 그대로.
            patch.forEach((key, value) -> {
                if (!ROW_HANDLE.equals(key)) {
                    values.put(key, value);
                }
            });
            if (!values.equals(row.values())) {
                changed++;
            }
            merged.add(new MappedRow(row.rowNumber(), values, row.attributes()));
        }

        recorder.recordSucceeded(tenantId, script, connectorRunId, rows.size(), changed, result);
        log.info("후처리 완료 — 스크립트={} 행={}건 바뀜={}건 {}ms",
                script.getName(), rows.size(), changed, result.elapsedMs());
        return merged;
    }

    /**
     * 어느 행인지 <b>우리가 붙인 손잡이</b>로 맞춘다.
     *
     * <p>품목코드로 맞추면 그 칸만은 바꿀 수 없다 — 바꾸는 순간 어느 행에 반영할지 알 수
     * 없어지기 때문이다. 「모든 칸을 열어 둔다」 는 원칙이 거기서 깨진다.
     *
     * <p>순서로 맞추지도 않는다. 스크립트가 행을 걸러내거나 순서를 바꾸면 <b>다른 품목의 값이
     * 엉뚱한 행에 붙는다.</b>
     */
    private static Map<Integer, Map<String, Object>> index(List<Map<String, Object>> rows) {
        Map<Integer, Map<String, Object>> byHandle = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object handle = row.get(ROW_HANDLE);
            if (handle instanceof Number number) {
                byHandle.put(number.intValue(), row);
            }
        }
        return byHandle;
    }
}
