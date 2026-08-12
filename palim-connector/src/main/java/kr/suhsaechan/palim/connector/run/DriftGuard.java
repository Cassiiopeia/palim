package kr.suhsaechan.palim.connector.run;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMap;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.schema.DriftDetector;
import kr.suhsaechan.palim.connector.schema.DriftVerdict;
import kr.suhsaechan.palim.connector.schema.SchemaSnapshot;
import kr.suhsaechan.palim.connector.source.SourceSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 드리프트 검사와 차단.
 *
 * <p>판정은 {@link DriftDetector}(순수 함수)가 하고, 여기서는 <b>차단할지</b>와 <b>어떻게
 * 알릴지</b>만 정한다. 경고는 로그로 남기고 진행한다 — 막지 않을 변화까지 실행을 세우면
 * 사람이 감지를 꺼버린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriftGuard {

    private final DriftDetector detector;

    public void verify(ConnectorMapping mapping, List<ConnectorFieldMap> fieldMaps,
                       SourceSchema current) {
        Set<String> mappedFields = fieldMaps.stream()
                .map(ConnectorFieldMap::getSourceField)
                .collect(Collectors.toSet());

        DriftVerdict verdict = detector.detect(
                new SchemaSnapshot(mapping.confirmedFields()), current, mappedFields);

        if (verdict.blocking()) {
            throw new BusinessException(ErrorCode.SCHEMA_DRIFT_DETECTED, verdict.summary());
        }
        if (!verdict.summary().isEmpty()) {
            log.info("원천 양식 변화(진행) — {}", verdict.summary());
        }
    }
}
