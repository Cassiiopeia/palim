package kr.suhsaechan.palim.web.connector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMap;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMapRepository;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.MappingStatus;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetField;
import kr.suhsaechan.palim.connector.model.TargetFieldRepository;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.source.SourceContext;
import kr.suhsaechan.palim.connector.source.SourceReaderRegistry;
import kr.suhsaechan.palim.connector.source.SourceSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 커넥터 관리.
 *
 * <p>화면이 쓰는 쓰기 동작을 모은다. 조회는 {@link ConnectorQueryService} 가 담당한다 —
 * 읽기는 여러 테이블을 조인해야 해서 JPA 로는 N+1 이 난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorAdminService {

    /** 멀티테넌시는 컬럼만 있고 아직 하나로 운영한다. 필터 적용 시 이 상수가 교체된다. */
    public static final UUID DEFAULT_TENANT =
            UUID.fromString("00000000-0000-7000-8000-000000000001");

    private final ConnectorRepository connectorRepository;
    private final ConnectorMappingRepository mappingRepository;
    private final ConnectorFieldMapRepository fieldMapRepository;
    private final TargetModelRepository targetModelRepository;
    private final TargetFieldRepository targetFieldRepository;
    private final SourceReaderRegistry readers;

    @Transactional(readOnly = true)
    public List<TargetModel> targetModels() {
        return targetModelRepository.findByTenantIdOrderByCode(DEFAULT_TENANT);
    }

    @Transactional(readOnly = true)
    public Connector connector(UUID id) {
        return connectorRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTOR_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<TargetField> targetFields(UUID connectorId) {
        Connector connector = connector(connectorId);
        return targetFieldRepository.findByTargetModelIdOrderBySortOrder(
                connector.getTargetModelId());
    }

    @Transactional
    public Connector create(String code, String name, UUID targetModelId, SourceType sourceType,
                            String defaultUnit) {
        connectorRepository.findByTenantIdAndCode(DEFAULT_TENANT, code).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 쓰는 코드입니다: " + code);
        });
        return connectorRepository.save(Connector.of(DEFAULT_TENANT, code, name, targetModelId,
                sourceType, defaultUnit));
    }

    /**
     * 업로드 파일에서 원천 구조를 읽는다.
     *
     * <p>임시 파일은 <b>호출자가 지운다.</b> 스키마만 보고 끝나는 경우와 이어서 실행까지 하는
     * 경우가 있어, 여기서 지우면 두 번째 흐름이 깨진다.
     */
    public SourceSchema readSchema(Connector connector, Path file, int headerRow) {
        return readers.of(connector.getSourceType())
                .readSchema(SourceContext.ofUpload(connector.getId(), file, headerRow));
    }

    /**
     * 파일 없이 원천 구조를 읽는다.
     *
     * <p>API 로 연결한 원천은 올릴 파일이 없다. 그런데도 매핑 화면이 파일을 요구하면 사용자는
     * <b>이미 받아온 자료를 엑셀로 다시 만들어 올려야</b> 한다. 실제로 그 상태였다.
     *
     * <p>화면을 열 때마다 새로 받는다. 상대가 칸을 바꾸면 그 자리에서 드러나기 때문이다 —
     * 저장해 둔 목록을 쓰면 이미 바뀐 원천에 옛 매핑을 그리게 된다.
     */
    public SourceSchema readSchema(Connector connector) {
        return readers.of(connector.getSourceType()).readSchema(
                new SourceContext(connector.getId(), null, 1, null, connector.getSourceConfig()));
    }

    /** 이 원천이 파일 없이 스스로 자료를 가져올 수 있는가. 화면이 업로드 칸을 감출지 정한다. */
    public boolean fetchesItself(Connector connector) {
        return connector.getSourceType() != SourceType.UPLOAD;
    }

    /**
     * 매핑 초안 저장.
     *
     * <p>기존 초안이 있으면 그것을 갱신하고, 없으면 다음 버전으로 만든다. 화면에서 매핑을
     * 고칠 때마다 버전이 늘면 이력이 의미 없는 숫자로 가득 찬다 — 버전은 <b>확정 단위</b>다.
     */
    @Transactional
    public ConnectorMapping saveDraft(UUID connectorId, SourceSchema schema,
                                      List<FieldMappingForm> forms) {
        Connector connector = connector(connectorId);

        ConnectorMapping draft = mappingRepository
                .findByConnectorIdAndStatus(connectorId, MappingStatus.DRAFT)
                .orElseGet(() -> ConnectorMapping.draft(connector.getTenantId(), connectorId,
                        nextVersion(connectorId), Map.of()));

        draft.replaceSchema(Map.of("fields", schema.fields()));
        ConnectorMapping saved = mappingRepository.save(draft);

        // 삭제를 먼저 DB 에 반영한다. JPA 는 flush 시점을 스스로 정하므로 그대로 두면
        // insert 가 먼저 나가 (mapping_id, target_field_key) 유니크 제약을 위반한다 —
        // 사람이 매핑을 두 번 저장하는 것은 화면에서 가장 흔한 동작이다.
        fieldMapRepository.deleteByMappingId(saved.getId());
        fieldMapRepository.flush();

        List<ConnectorFieldMap> maps = forms.stream()
                .filter(FieldMappingForm::isConnected)
                .map(form -> ConnectorFieldMap.of(connector.getTenantId(), saved.getId(),
                        form.sourceField(), form.targetFieldKey(), form.toRule(), form.order()))
                .toList();
        fieldMapRepository.saveAll(maps);

        return saved;
    }

    /**
     * 매핑 확정.
     *
     * <p>기존 ACTIVE 는 ARCHIVED 로 내린다. 커넥터당 ACTIVE 는 하나뿐이며 그 보장은 DB
     * 부분 유니크 인덱스가 한다 — 여기서만 검사하면 동시 요청에서 뚫린다.
     */
    @Transactional
    public ConnectorMapping activate(UUID connectorId) {
        ConnectorMapping draft = mappingRepository
                .findByConnectorIdAndStatus(connectorId, MappingStatus.DRAFT)
                .orElseThrow(() -> new BusinessException(ErrorCode.MAPPING_NOT_FOUND));

        mappingRepository.findByConnectorIdAndStatus(connectorId, MappingStatus.ACTIVE)
                .ifPresent(active -> {
                    active.archive();
                    mappingRepository.saveAndFlush(active);
                });

        draft.activate();
        return mappingRepository.save(draft);
    }

    @Transactional(readOnly = true)
    public List<ConnectorFieldMap> currentFieldMaps(UUID connectorId) {
        return mappingRepository.findFirstByConnectorIdOrderByVersionDesc(connectorId)
                .map(mapping -> fieldMapRepository.findByMappingIdOrderBySortOrder(mapping.getId()))
                .orElseGet(List::of);
    }

    private int nextVersion(UUID connectorId) {
        return mappingRepository.findFirstByConnectorIdOrderByVersionDesc(connectorId)
                .map(mapping -> mapping.getVersion() + 1)
                .orElse(1);
    }

    /**
     * 업로드 파일을 임시 경로에 저장한다.
     *
     * <p>원본을 보관하지 않는다. 파싱이 끝나면 스냅샷 라인이 DB 에 남으므로 원본이 필요 없고,
     * 발주사 실데이터를 서버에 쌓지 않는 편이 안전하다.
     */
    public Path saveTemporary(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "파일을 선택하세요");
        }
        try {
            String suffix = suffixOf(file.getOriginalFilename());
            Path temp = Files.createTempFile("palim-upload-", suffix);
            file.transferTo(temp);
            return temp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // 임시 파일이라 지우지 못해도 업무에 영향이 없다. OS 가 정리한다.
            log.debug("임시 파일 삭제 실패 — {}", file, e);
        }
    }

    /** 확장자를 유지한다. 파서가 CSV 와 엑셀을 확장자로 구분하기 때문이다. */
    private String suffixOf(String originalFilename) {
        if (originalFilename == null) {
            return ".csv";
        }
        int dot = originalFilename.lastIndexOf('.');
        return dot < 0 ? ".csv" : originalFilename.substring(dot).toLowerCase();
    }
}
