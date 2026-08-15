package kr.suhsaechan.palim.connector.write;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.connector.key.NaturalKeyBuilder;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.run.ConnectorStaging;
import kr.suhsaechan.palim.connector.run.ConnectorStagingRepository;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.transform.MappedRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * TEST 실행 적재기.
 *
 * <p>운영 테이블에 <b>닿지 않는다.</b> 표준 테이블에 넣고 지우는 방식도 가능하지만 두 가지가
 * 깨진다 — 지우기 전에 도메인 로직이 읽으면 오염된 결과가 나오고, UPSERT 로 덮어쓴 행은
 * 소유 실행이 바뀌어 "그 실행분만 삭제"가 성립하지 않는다.
 *
 * <p>변환 결과를 그대로 담으므로 화면에서 "이 값이 이렇게 들어갑니다"를 보여줄 수 있다.
 */
@Component
@RequiredArgsConstructor
public class StagingWriter implements RecordWriter {

    private final ConnectorStagingRepository repository;
    private final NaturalKeyBuilder keyBuilder;

    /**
     * 시각을 <b>담을 때</b> 사람이 읽는 글자로 굳힌다.
     *
     * <p>JSON 은 시각이라는 것을 모른다. {@link Instant} 를 그냥 넣으면 «1970년부터 몇 초»
     * 라는 숫자로 굳어 {@code 1786719600} 이 저장된다. 그러면 화면이 그것을 다시 시각으로
     * 되돌려야 하고, <b>어느 칸이 시각인지 화면이 알아야</b> 한다. 판단이 두 군데로 갈리는
     * 구조라 언젠가 어긋난다.
     *
     * <p>담을 때 굳혀 두면 화면은 <b>받은 값을 그대로</b> 보여주기만 하면 된다. 이 표는
     * 「진짜로 넣기 전에 눈으로 보는」 자리라, 화면이 값을 손대는 순간 확인이 거짓이 된다.
     */
    private static Map<String, Object> readableMoments(Map<String, Object> values) {
        Map<String, Object> readable = new LinkedHashMap<>();
        values.forEach((key, value) -> readable.put(key,
                value instanceof Instant moment ? moment.toString() : value));
        return readable;
    }

    @Override
    public RunMode mode() {
        return RunMode.TEST;
    }

    @Override
    @Transactional
    public WriteResult write(UUID tenantId, UUID runId, TargetModel model,
                             List<MappedRow> chunk) {
        List<ConnectorStaging> entities = chunk.stream()
                .map(row -> ConnectorStaging.of(tenantId, runId, row.rowNumber(),
                        keyBuilder.build(row.values(), model.getNaturalKeyFields()),
                        readableMoments(row.values())))
                .toList();

        repository.saveAll(entities);
        return WriteResult.of(entities.size());
    }
}
