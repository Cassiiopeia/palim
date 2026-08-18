package kr.suhsaechan.palim.connector.run;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMap;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMapRepository;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.define.Intake;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.MappingStatus;
import kr.suhsaechan.palim.connector.model.TargetField;
import kr.suhsaechan.palim.connector.model.TargetFieldRepository;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.transform.FieldMapping;
import kr.suhsaechan.palim.connector.transform.TargetFieldSpec;
import kr.suhsaechan.palim.connector.transform.TransformRule;
import kr.suhsaechan.palim.connector.transform.TransformType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행에 필요한 정의를 모아 온다.
 *
 * <p>조회를 오케스트레이터에서 직접 하면 그 클래스가 리포지토리 대여섯 개를 들고 흐름이
 * 보이지 않는다. 조회와 값 객체 변환만 여기서 담당한다.
 */
@Component
@RequiredArgsConstructor
public class ConnectorLoader {

    private final ConnectorRepository connectorRepository;
    private final ConnectorMappingRepository mappingRepository;
    private final ConnectorFieldMapRepository fieldMapRepository;
    private final TargetModelRepository targetModelRepository;
    private final TargetFieldRepository targetFieldRepository;

    @Transactional(readOnly = true)
    public Connector connector(UUID connectorId) {
        return connectorRepository.findById(connectorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTOR_NOT_FOUND));
    }

    /**
     * 실행에 쓸 매핑 버전.
     *
     * <p>LIVE 는 확정된 매핑만 쓴다. 확정 이력이 없으면 "어느 정의로 넣은 데이터인가"를 나중에
     * 설명할 수 없고 되돌릴 근거도 없다. TEST 는 최신 초안으로도 돌아야 한다 — 확정 전에
     * 결과를 보는 것이 목적이기 때문이다.
     */
    @Transactional(readOnly = true)
    public ConnectorMapping mappingFor(Connector connector, RunMode mode) {
        return mappingFor(connector, Intake.AUTO, mode);
    }

    /**
     * 실행에 쓸 매핑 버전 — <b>들어오는 길에 맞는 것.</b>
     *
     * <p>엑셀 열 이름은 API 칸 이름과 다르다. 길을 안 가리면 파일을 올리는 순간 API 용 칸
     * 맞추기가 걸려 <b>전 행이 실패</b>한다.
     */
    @Transactional(readOnly = true)
    public ConnectorMapping mappingFor(Connector connector, Intake intake, RunMode mode) {
        if (mode == RunMode.LIVE) {
            return mappingRepository
                    .findByConnectorIdAndIntakeAndStatus(connector.getId(), intake,
                            MappingStatus.ACTIVE)
                    .orElseThrow(() -> new BusinessException(ErrorCode.MAPPING_NOT_ACTIVE));
        }
        return mappingRepository
                .findByConnectorIdAndIntakeOrderByVersionDesc(connector.getId(), intake).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.MAPPING_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public TargetModel targetModel(Connector connector) {
        return targetModelRepository.findById(connector.getTargetModelId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTOR_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<ConnectorFieldMap> fieldMaps(ConnectorMapping mapping) {
        return fieldMapRepository.findByMappingIdOrderBySortOrder(mapping.getId());
    }

    /** 엔티티를 변환 엔진이 쓰는 값 객체로 바꾼다. 엔진이 영속 계층을 모르게 하기 위해서다. */
    public List<FieldMapping> toFieldMappings(List<ConnectorFieldMap> fieldMaps) {
        return fieldMaps.stream()
                .map(map -> new FieldMapping(map.getSourceField(), map.getTargetFieldKey(),
                        toRule(map.getTransformRule())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TargetFieldSpec> fieldSpecs(TargetModel model) {
        return targetFieldRepository.findByTargetModelIdOrderBySortOrder(model.getId()).stream()
                .map(field -> new TargetFieldSpec(field.getFieldKey(), field.getDataType(),
                        field.isRequired(), field.getDefaultValue()))
                .toList();
    }

    /** JSONB 로 저장된 규칙을 값 객체로. 타입이 없거나 이상하면 변환 없음으로 본다. */
    @SuppressWarnings("unchecked")
    private TransformRule toRule(java.util.Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return TransformRule.none();
        }
        Object type = raw.get("type");
        if (type == null) {
            return TransformRule.none();
        }
        try {
            Object params = raw.get("params");
            return TransformRule.of(TransformType.valueOf(type.toString()),
                    params instanceof java.util.Map<?, ?> map
                            ? (java.util.Map<String, String>) map
                            : java.util.Map.of());
        } catch (IllegalArgumentException e) {
            // 알 수 없는 유형이면 원본을 그대로 넘긴다. 실행을 막을 이유는 아니다.
            return TransformRule.none();
        }
    }

    /** {@code base_quantity} 필드가 있는 모델만 단위 환산 대상이다. */
    public boolean hasBaseQuantity(List<TargetFieldSpec> specs) {
        return specs.stream().anyMatch(spec -> "base_quantity".equals(spec.fieldKey()));
    }

    public List<TargetField> targetFields(TargetModel model) {
        return targetFieldRepository.findByTargetModelIdOrderBySortOrder(model.getId());
    }
}
