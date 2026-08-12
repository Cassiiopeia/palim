package kr.suhsaechan.palim.connector.run;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMap;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.source.SourceContext;
import kr.suhsaechan.palim.connector.source.SourceReader;
import kr.suhsaechan.palim.connector.source.SourceReaderRegistry;
import kr.suhsaechan.palim.connector.source.SourceRow;
import kr.suhsaechan.palim.connector.transform.FieldMapping;
import kr.suhsaechan.palim.connector.transform.MappedRow;
import kr.suhsaechan.palim.connector.transform.TargetFieldSpec;
import kr.suhsaechan.palim.connector.transform.TransformEngine;
import kr.suhsaechan.palim.connector.write.RecordWriter;
import kr.suhsaechan.palim.connector.write.WriterSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 실행 오케스트레이터.
 *
 * <p>읽기 → 드리프트 검사 → 변환 → 환산 → 적재를 순서대로 돌린다. 각 단계는 독립 컴포넌트이며
 * 이 클래스는 <b>흐름과 실패 처리</b>만 담당한다.
 *
 * <p>클래스 전체에 트랜잭션을 걸지 않는다. 걸면 청크가 하나의 트랜잭션으로 묶여 <b>부분 실패를
 * 표현할 수 없다</b> — 마지막 행에서 터지면 앞의 999행도 함께 사라진다. 적재기의
 * {@code @Transactional} 이 청크 단위 커밋을 담당하고, 실행 기록은 각자 커밋된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorRunner {

    /**
     * 청크 크기.
     *
     * <p>행 단위 커밋은 대량 적재에서 느리고, 전체를 한 트랜잭션에 묶으면 부분 실패가 사라진다.
     */
    private static final int CHUNK_SIZE = 500;

    private final ConnectorLoader loader;
    private final SourceReaderRegistry readers;
    private final DriftGuard driftGuard;
    private final TransformEngine transformEngine;
    private final QuantityNormalizer quantityNormalizer;
    private final WriterSelector writerSelector;
    private final ConnectorRunRepository runRepository;
    private final ConnectorRunErrorRepository errorRepository;

    public ConnectorRun run(RunRequest request) {
        Connector connector = loader.connector(request.connectorId());
        ConnectorMapping mapping = loader.mappingFor(connector, request.mode());

        // LIVE 는 확정된 매핑에서만. DRAFT 로 실제 데이터를 넣으면 되돌릴 근거가 없다.
        if (request.mode() == RunMode.LIVE && !mapping.isActive()) {
            throw new BusinessException(ErrorCode.MAPPING_NOT_ACTIVE);
        }
        if (runRepository.existsByConnectorIdAndStatus(connector.getId(), RunStatus.RUNNING)) {
            throw new BusinessException(ErrorCode.CONNECTOR_ALREADY_RUNNING);
        }

        ConnectorRun run = runRepository.save(ConnectorRun.start(
                connector.getTenantId(), connector.getId(), mapping.getId(),
                mapping.getVersion(), request.mode(), request.trigger()));

        try {
            return execute(connector, mapping, run, request);
        } catch (BusinessException e) {
            log.warn("커넥터 실행 실패 — {} ({})", connector.getCode(), e.getErrorCode(), e);
            run.fail(e.getMessage());
            return runRepository.save(run);
        }
    }

    private ConnectorRun execute(Connector connector, ConnectorMapping mapping,
                                 ConnectorRun run, RunRequest request) {
        TargetModel model = loader.targetModel(connector);
        List<ConnectorFieldMap> fieldMaps = loader.fieldMaps(mapping);
        List<FieldMapping> mappings = loader.toFieldMappings(fieldMaps);
        List<TargetFieldSpec> specs = loader.fieldSpecs(model);
        boolean hasBaseQuantity = loader.hasBaseQuantity(specs);

        SourceReader reader = readers.of(connector.getSourceType());
        SourceContext context = new SourceContext(connector.getId(), request.file(),
                request.headerRow(), connector.getCursorValue(), connector.getSourceConfig());

        driftGuard.verify(mapping, fieldMaps, reader.readSchema(context));

        RecordWriter writer = writerSelector.of(run.getRunMode());
        List<MappedRow> buffer = new ArrayList<>(CHUNK_SIZE);
        int total = 0;
        int success = 0;
        int failed = 0;

        try (Stream<SourceRow> rows = reader.read(context)) {
            Iterator<SourceRow> iterator = rows.iterator();
            while (iterator.hasNext()) {
                SourceRow sourceRow = iterator.next();
                total++;
                try {
                    MappedRow mapped = transformEngine.map(sourceRow, mappings, specs);
                    buffer.add(quantityNormalizer.normalize(connector.getTenantId(), mapped,
                            hasBaseQuantity, connector.getDefaultUnit()));
                } catch (BusinessException e) {
                    // 행 단위 실패. 나머지는 계속 적재한다.
                    failed++;
                    recordError(connector, run, sourceRow, e);
                    continue;
                }
                if (buffer.size() >= CHUNK_SIZE) {
                    success += flush(connector, run, model, writer, buffer);
                }
            }
        }
        success += flush(connector, run, model, writer, buffer);

        run.finish(total, success, failed);
        return runRepository.save(run);
    }

    private int flush(Connector connector, ConnectorRun run, TargetModel model,
                      RecordWriter writer, List<MappedRow> buffer) {
        if (buffer.isEmpty()) {
            return 0;
        }
        int written = writer.write(connector.getTenantId(), run.getId(), model,
                List.copyOf(buffer)).written();
        buffer.clear();
        return written;
    }

    private void recordError(Connector connector, ConnectorRun run, SourceRow sourceRow,
                             BusinessException e) {
        errorRepository.save(ConnectorRunError.of(connector.getTenantId(), run.getId(),
                sourceRow.rowNumber(), sourceRow.values(), e.getErrorCode().name(),
                e.getMessage()));
    }
}
