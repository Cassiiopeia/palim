package kr.suhsaechan.palim.connector.write;

import java.util.List;
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
                        row.values()))
                .toList();

        repository.saveAll(entities);
        return WriteResult.of(entities.size());
    }
}
