package kr.suhsaechan.palim.connector.write;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.transform.MappedRow;

/**
 * 적재기.
 *
 * <p>TEST 와 LIVE 가 <b>서로 다른 구현</b>을 쓴다. TEST 는 스테이징에만 쓰므로 운영 데이터에
 * 닿지 않고, 그래서 부담 없이 테스트할 수 있다 — 부담이 있으면 아무도 테스트하지 않는다.
 */
public interface RecordWriter {

    RunMode mode();

    WriteResult write(UUID tenantId, UUID runId, TargetModel model, List<MappedRow> chunk);
}
