package kr.suhsaechan.palim.connector.run;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import kr.suhsaechan.palim.connector.source.SourceSchema;
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
        log.debug("실행 요청 접수 — 커넥터id={} 모드={} 트리거={} 파일={} 헤더행={}",
                request.connectorId(), request.mode(), request.trigger(), request.file(),
                request.headerRow());

        Connector connector = loader.connector(request.connectorId());
        ConnectorMapping mapping = loader.mappingFor(connector, request.mode());
        log.debug("정의 적재 — 커넥터={} 원천유형={} 매핑id={} 매핑버전={} 매핑상태={}",
                connector.getCode(), connector.getSourceType(), mapping.getId(),
                mapping.getVersion(), mapping.getStatus());

        // LIVE 는 확정된 매핑에서만. DRAFT 로 실제 데이터를 넣으면 되돌릴 근거가 없다.
        if (request.mode() == RunMode.LIVE && !mapping.isActive()) {
            log.warn("LIVE 실행 차단 — 매핑이 확정 상태가 아니다. 커넥터={} 매핑id={} 매핑버전={} 매핑상태={}",
                    connector.getCode(), mapping.getId(), mapping.getVersion(), mapping.getStatus());
            throw new BusinessException(ErrorCode.MAPPING_NOT_ACTIVE);
        }
        if (runRepository.existsByConnectorIdAndStatus(connector.getId(), RunStatus.RUNNING)) {
            log.warn("중복 실행 차단 — 같은 커넥터가 이미 실행 중이다. 커넥터={} 커넥터id={}",
                    connector.getCode(), connector.getId());
            throw new BusinessException(ErrorCode.CONNECTOR_ALREADY_RUNNING);
        }

        ConnectorRun run = runRepository.save(ConnectorRun.start(
                connector.getTenantId(), connector.getId(), mapping.getId(),
                mapping.getVersion(), request.mode(), request.trigger()));
        log.info("실행 시작 — 실행id={} 커넥터={} 모드={} 트리거={} 매핑버전={}",
                run.getId(), connector.getCode(), run.getRunMode(), request.trigger(),
                mapping.getVersion());

        try {
            return execute(connector, mapping, run, request);
        } catch (BusinessException e) {
            log.warn("커넥터 실행 실패 — 실행id={} 커넥터={} ({})",
                    run.getId(), connector.getCode(), e.getErrorCode(), e);
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
        log.debug("정의 로딩 — 실행id={} 대상모델={} 저장소={} 필드매핑={}건 타깃필드={}건 기준수량={}",
                run.getId(), model.getCode(), model.getStorage(), fieldMaps.size(), specs.size(),
                hasBaseQuantity);

        SourceReader reader = readers.of(connector.getSourceType());
        SourceContext context = new SourceContext(connector.getId(), request.file(),
                request.headerRow(), connector.getCursorValue(), connector.getSourceConfig());
        // sourceConfig 에는 API URL·응답 경로 같은 비민감 값만 들어온다(비밀값은 credentialRef)
        log.debug("원천 준비 — 실행id={} 원천유형={} 리더={} 파일={} 헤더행={} 커서={} 설정={}",
                run.getId(), connector.getSourceType(), reader.getClass().getSimpleName(),
                request.file(), request.headerRow(), connector.getCursorValue(),
                connector.getSourceConfig());

        SourceSchema currentSchema = reader.readSchema(context);
        log.debug("원천 스키마 조회 — 실행id={} 필드={}건 원천전체={}건 필드목록={}",
                run.getId(), currentSchema.fields().size(), currentSchema.totalCount(),
                currentSchema.fields());
        driftGuard.verify(mapping, fieldMaps, currentSchema);
        log.debug("드리프트 검사 통과 — 실행id={} 매핑버전={}", run.getId(), mapping.getVersion());

        RecordWriter writer = writerSelector.of(run.getRunMode());
        log.debug("적재기 선택 — 실행id={} 모드={} 적재기={}",
                run.getId(), run.getRunMode(), writer.getClass().getSimpleName());
        Map<String, Object> systemValues = systemValues(connector);
        log.debug("시스템이 채우는 값 — 실행id={} {}", run.getId(), systemValues);

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
                    MappedRow mapped =
                            transformEngine.map(sourceRow, mappings, specs, systemValues);
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
        log.debug("원천 읽기 종료 — 실행id={} 읽은행={}건 변환실패={}건 잔여버퍼={}건",
                run.getId(), total, failed, buffer.size());
        success += flush(connector, run, model, writer, buffer);

        run.finish(total, success, failed);
        if (failed > 0) {
            log.warn("실패 행 있음 — 실행id={} 커넥터={} 실패={}건/전체={}건 (상세는 connector_run_error)",
                    run.getId(), connector.getCode(), failed, total);
        }
        log.info("실행 종료 — 실행id={} 커넥터={} 상태={} 모드={} 읽기={}건 적재={}건 실패={}건",
                run.getId(), connector.getCode(), run.getStatus(), run.getRunMode(),
                total, success, failed);
        return runRepository.save(run);
    }

    private int flush(Connector connector, ConnectorRun run, TargetModel model,
                      RecordWriter writer, List<MappedRow> buffer) {
        if (buffer.isEmpty()) {
            return 0;
        }
        int pending = buffer.size();
        log.debug("청크 적재 시작 — 실행id={} 대상모델={} 저장소={} {}건",
                run.getId(), model.getCode(), model.getStorage(), pending);
        int written = writer.write(connector.getTenantId(), run.getId(), model,
                List.copyOf(buffer)).written();
        buffer.clear();
        log.debug("청크 적재 완료 — 실행id={} 요청={}건 반영={}건", run.getId(), pending, written);
        if (written != pending) {
            log.warn("청크 반영 수 불일치 — 실행id={} 커넥터={} 요청={}건 반영={}건",
                    run.getId(), connector.getCode(), pending, written);
        }
        return written;
    }

    /**
     * 사람이 고르지 않고 시스템이 채우는 값.
     *
     * <p>이 셋은 상대가 보내주는 것이 아니라 <b>우리가 이미 아는 값</b>이다. 어느 연동에서
     * 왔는지, 며칟날 기준으로 물어봤는지, 언제 받아왔는지. 화면에서 물어보면 사장님은 넣을 칸이
     * 없어 매핑을 끝낼 수 없다.
     *
     * <p><b>기준 시각을 날짜(그날 0시)로 두는 이유</b> — 대조는 양쪽 기준 시각이 정확히 같아야
     * 돈다. 조회한 시각을 그대로 쓰면 한쪽을 06:00:03에, 다른 쪽을 06:00:47에 뽑았을 때 44초
     * 차이로 비교를 거부한다. 날짜로 맞추면 새벽 아무 때나 각각 수집해도 같은 날 것끼리 견준다.
     * 같은 날 다시 수집하면 덮어쓰는데, 그것이 맞다 — 나중 답이 그 사이 입출고까지 반영한
     * 더 정확한 값이다.
     */
    private Map<String, Object> systemValues(Connector connector) {
        Map<String, Object> values = new LinkedHashMap<>();
        // 대조 정의가 원천을 이 이름으로 가리킨다. 커넥터 코드가 곧 «어디서 온 자료인지» 다.
        values.put("source", connector.getCode());
        values.put("base_at", baseInstant(connector));
        values.put("collected_at", Instant.now());
        return values;
    }

    /**
     * 어느 날짜 기준 자료인가.
     *
     * <p>기준일이 정해져 있지 않으면 오늘이다 — 매일 도는 수집은 「오늘 재고」를 받는 것이
     * 정상이다. 형식이 깨져 있어도 <b>수집 전체를 멈추지 않는다.</b> 이 값 하나 때문에 그날
     * 자료를 통째로 못 받는 것보다, 오늘로 보고 받아 두는 편이 낫다.
     */
    private Instant baseInstant(Connector connector) {
        Map<String, Object> config = connector.getSourceConfig();
        Object raw = config == null ? null : config.get("baseDate");
        String text = raw == null ? null : String.valueOf(raw).trim();

        LocalDate date = LocalDate.now();
        if (text != null && !text.isBlank()) {
            try {
                date = LocalDate.parse(text);
            } catch (DateTimeParseException e) {
                log.warn("기준일 형식을 읽지 못해 오늘로 봅니다 — connector={} 값='{}'",
                        connector.getCode(), text);
            }
        }
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private void recordError(Connector connector, ConnectorRun run, SourceRow sourceRow,
                             BusinessException e) {
        log.warn("행 처리 실패(건너뜀) — 실행id={} 커넥터={} 행번호={} 오류={} 사유={}",
                run.getId(), connector.getCode(), sourceRow.rowNumber(),
                e.getErrorCode().name(), e.getMessage());
        log.debug("실패 행 원본 값 — 실행id={} 행번호={} 값={}",
                run.getId(), sourceRow.rowNumber(), sourceRow.values());
        errorRepository.save(ConnectorRunError.of(connector.getTenantId(), run.getId(),
                sourceRow.rowNumber(), sourceRow.values(), e.getErrorCode().name(),
                e.getMessage()));
    }
}
