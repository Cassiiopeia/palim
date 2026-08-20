package kr.suhsaechan.palim.web.connector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMap;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMapRepository;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.Intake;
import kr.suhsaechan.palim.connector.define.MappingStatus;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.secret.ConnectorSecretService;
import kr.suhsaechan.palim.connector.source.http.ApiAuthPreset;
import kr.suhsaechan.palim.connector.source.http.MenuPathFinder;
import kr.suhsaechan.palim.connector.model.TargetField;
import kr.suhsaechan.palim.connector.model.TargetFieldRepository;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.source.SourceContext;
import kr.suhsaechan.palim.connector.source.SourceReaderRegistry;
import kr.suhsaechan.palim.connector.suggest.FieldMappingMemoryService;
import kr.suhsaechan.palim.connector.source.SourceSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
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

    private final MenuPathFinder menuPathFinder;
    private final ConnectorSecretService secretService;
    private final ConnectorRepository connectorRepository;
    private final ConnectorMappingRepository mappingRepository;
    private final ConnectorFieldMapRepository fieldMapRepository;
    private final TargetModelRepository targetModelRepository;
    private final TargetFieldRepository targetFieldRepository;
    private final SourceReaderRegistry readers;
    private final FieldMappingMemoryService memories;

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
    /**
     * 올린 파일에서 칸을 읽는다.
     *
     * <p><b>연동 유형이 아니라 「파일이 왔다」 는 사실로 리더를 고른다.</b> 유형으로 고르면
     * 스스로 가져오는 연동에 파일을 올려도 API 를 부르고, 그 API 가 죽어 있어서 파일을 올린
     * 것이므로 <b>우회로가 우회로가 아니게 된다.</b>
     */
    public SourceSchema readSchema(Connector connector, Path file, int headerRow) {
        return readers.of(SourceType.UPLOAD)
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

    /** 담을 표준 모델 코드. 자동 추천이 어느 항목 목록을 볼지 정하는 데 쓴다. */
    @Transactional(readOnly = true)
    public String targetModelCode(Connector connector) {
        return targetModelRepository.findById(connector.getTargetModelId())
                .map(TargetModel::getCode)
                .orElse("");
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
    /**
     * 받아온 칸과 값을 <b>그대로 담아 둔다.</b>
     *
     * <p>칸 이름만 담으면 화면이 「실제로 들어올 값」을 보여줄 수 없어, 매번 상대를 다시 불러야
     * 한다. 그러면 화면을 열 때마다 원격 호출이 나가고, 상대가 한 번 삐끗하면 화면이 안 열린다.
     * 실제로 그렇게 500 이 났다.
     */
    static Map<String, Object> snapshotOf(SourceSchema schema) {
        return Map.of(
                "fields", schema.fields(),
                "sampleRows", schema.sampleRows(),
                "totalCount", schema.totalCount());
    }

    /**
     * 담아 둔 것을 되살린다. 담긴 것이 없으면 {@code null} — 아직 한 번도 받아오지 않았다는 뜻이다.
     */
    @SuppressWarnings("unchecked")
    static SourceSchema restore(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        Object fields = snapshot.get("fields");
        if (!(fields instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object rows = snapshot.get("sampleRows");
        Object total = snapshot.get("totalCount");
        return new SourceSchema(
                list.stream().map(String::valueOf).toList(),
                rows instanceof List<?> sample
                        ? sample.stream().map(row -> (Map<String, Object>) row).toList()
                        : List.of(),
                total instanceof Number n ? n.intValue() : -1);
    }

    /**
     * 그 길의 초안 — 없으면 만든다.
     *
     * <p>길을 안 가리고 찾으면 파일용 초안을 만들려는데 자동 수집용 초안이 잡힌다. 그러면 API
     * 칸 이름 위에 엑셀 열 이름을 덮어써 <b>둘 다 못 쓰게 된다.</b>
     */
    private ConnectorMapping draftFor(Connector connector, Intake intake) {
        return mappingRepository
                .findByConnectorIdAndIntakeOrderByVersionDesc(connector.getId(), intake).stream()
                .filter(candidate -> candidate.getStatus() == MappingStatus.DRAFT)
                .findFirst()
                .orElseGet(() -> ConnectorMapping.draft(connector.getTenantId(),
                        connector.getId(), nextVersion(connector.getId()), intake, Map.of()));
    }

    /**
     * 이 연동의 «받는 방법» 안내 — <b>비어 있을 수 없다.</b>
     *
     * <p>전에는 연결을 저장할 때 한 번 심었다. 그래서 <b>그 전에 만들어진 연동에는 비어
     * 있었고</b>, 화면은 「기본 안내 넣기」 단추를 눌러 달라고 했다. 정작 오래 쓴 연동일수록
     * 비어 있는 셈이고, <b>단추를 눌러야 생기는 안내는 급할 때 비어 있다.</b>
     *
     * <p>그래서 저장해 두지 않고 «읽을 때» 정한다. 사람이 고쳐 둔 것이 있으면 그것을 쓰고,
     * 없으면 프리셋이 아는 실제 경로를 그대로 보여준다. 저장은 <b>사람이 고쳤을 때만</b>
     * 일어나므로, 고쳐 둔 것을 코드가 말없이 되돌리는 일도 없다.
     */
    @Transactional(readOnly = true)
    public String fileGuideOf(Connector connector) {
        if (StringUtils.hasText(connector.getFileGuide())) {
            return connector.getFileGuide();
        }
        return presetOf(connector).map(ApiAuthPreset::getFileGuide).orElse("");
    }

    /**
     * 이 연동이 어느 프리셋으로 붙었나.
     *
     * <p>연동 코드가 «프리셋 이름-모델» 로 만들어지므로 앞자리로 되짚는다. 프리셋을 따로
     * 저장하지 않은 것은 코드가 이미 그 값을 담고 있기 때문이다.
     */
    private java.util.Optional<ApiAuthPreset> presetOf(Connector connector) {
        String code = connector.getCode() == null ? "" : connector.getCode();
        return java.util.Arrays.stream(ApiAuthPreset.values())
                .filter(preset -> code.startsWith(preset.name().toLowerCase(java.util.Locale.ROOT)))
                .findFirst();
    }

    /**
     * 상대 시스템에 <b>물어서</b> 메뉴 경로를 안내에 채운다.
     *
     * <p>코드에 적어 두면 상대가 메뉴를 바꾼 날 거짓말이 된다 — 사람은 그 거짓말을 믿고 없는
     * 메뉴를 찾는다. 그래서 물어보고, 상대가 바꾸면 다시 물으면 된다.
     *
     * <p>계정은 <b>서버 밖으로 나가지 않는다.</b> 매일 도는 수집과 같은 자리에서 같은 계정으로
     * 로그인한다.
     *
     * @return 찾은 메뉴 경로. 못 찾으면 빈 목록 — <b>지어내지 않는다</b>
     */
    @Transactional
    public List<String> probeMenuPath(UUID connectorId) {
        Connector connector = connector(connectorId);
        Map<String, String> config = connector.getSourceConfig().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> String.valueOf(entry.getValue()),
                        (a, b) -> a, LinkedHashMap::new));
        String password = secretService.find(connector.getCredentialRef(), "password")
                .orElse("");
        if (password.isBlank()) {
            return List.of();
        }

        List<String> labels = menuPathFinder.find(config, config.getOrDefault("userId", ""),
                password);
        if (labels.isEmpty()) {
            return List.of();
        }

        // 찾은 경로를 안내 «맨 앞» 에 둔다. 나머지 단계는 그대로 살린다 — 물어서 알아낸 것은
        // «메뉴 이름» 뿐이고, 조회 조건과 필요한 열은 여전히 안내가 알고 있다.
        //
        // 바탕은 저장된 값이 아니라 «지금 화면에 보이는 안내» 다. 저장된 값은 비어 있을 수
        // 있는데(사람이 한 번도 안 고쳤으면 그렇다) 그것을 바탕으로 삼으면 확인을 누른 순간
        // 단계 안내가 통째로 사라진다.
        String path = String.join(" > ", labels);
        String line = "받는 곳: %s".formatted(path);
        String existing = fileGuideOf(connector);
        connector.changeFileGuide(existing.contains(line) ? existing
                : (line + (existing.isBlank() ? "" : "\n\n" + existing)));
        connectorRepository.save(connector);
        return labels;
    }

    /** 파일을 어디서 받는지. 상대 사이트가 바뀌면 사람이 그 자리에서 고쳐 둔다. */
    @Transactional
    public void changeFileGuide(UUID connectorId, String guide) {
        Connector connector = connector(connectorId);
        connector.changeFileGuide(guide);
        connectorRepository.save(connector);
    }

    /** 확정해 둔 것이 있는가. 「확정할 것이 없다」와 「이미 확정했다」를 가르는 데 쓴다. */
    @Transactional(readOnly = true)
    public boolean hasActiveMapping(UUID connectorId) {
        return hasActiveMapping(connectorId, Intake.AUTO);
    }

    /** 길마다 확정판이 따로다 — 엑셀 열 이름과 API 칸 이름이 다르기 때문이다. */
    @Transactional(readOnly = true)
    public boolean hasActiveMapping(UUID connectorId, Intake intake) {
        return mappingRepository
                .findByConnectorIdAndIntakeAndStatus(connectorId, intake, MappingStatus.ACTIVE)
                .isPresent();
    }

    /** 그 길의 확정판 칸 수. 화면이 「7칸 맞춤 / 아직 없음」 을 말하는 데 쓴다. */
    @Transactional(readOnly = true)
    public int activeFieldCount(UUID connectorId, Intake intake) {
        return mappingRepository
                .findByConnectorIdAndIntakeAndStatus(connectorId, intake, MappingStatus.ACTIVE)
                .map(mapping -> fieldMapRepository
                        .findByMappingIdOrderBySortOrder(mapping.getId()).size())
                .orElse(0);
    }

    /**
     * 새로 온 것에 값이 없으면 갖고 있던 값을 지킨다.
     *
     * <p>매핑을 저장할 때 화면은 칸 <b>이름만</b> 돌려보낸다. 그것으로 담아 둔 것을 통째로
     * 덮으면 「실제로 들어올 값」이 전부 사라진다. 사람은 저장 한 번에 미리보기가 없어진
     * 이유를 알 수 없다.
     */
    private static SourceSchema keepSamples(SourceSchema incoming, SourceSchema stored) {
        if (stored == null || !incoming.sampleRows().isEmpty()) {
            return incoming;
        }
        // 칸 목록이 달라졌다면 옛 값은 맞지 않는다. 이름이 같을 때만 지킨다.
        if (!stored.fields().equals(incoming.fields())) {
            return incoming;
        }
        return new SourceSchema(incoming.fields(), stored.sampleRows(), stored.totalCount());
    }

    /**
     * 지난번에 받아 담아 둔 칸과 값.
     *
     * <p>확정한 것이 있으면 그것을, 없으면 작성 중인 것을 쓴다. 둘 다 없으면 {@code null} —
     * 한 번도 받아오지 않았다는 뜻이다.
     */
    @Transactional(readOnly = true)
    public SourceSchema storedSchema(UUID connectorId) {
        SourceSchema active = mappingRepository
                .findByConnectorIdAndStatus(connectorId, MappingStatus.ACTIVE)
                .map(mapping -> restore(mapping.getSourceSchema()))
                .orElse(null);
        // 확정판이 없으면 작성 중인 초안을 쓴다 — 값이 없어도 «칸» 은 있어야 한다.
        // 값이 있는 것만 골라 쓰면, 아직 값을 받아 오지 않은 연동에서 화면이 칸 목록조차
        // 못 그리고 폼 전체가 사라진다.
        SourceSchema base = active != null ? active : latestDraftSchema(connectorId);
        if (base == null) {
            return null;
        }
        // 확정판에 값이 없으면 초안이 갖고 있는 값을 빌려 온다.
        //
        // 확정은 «그때 있던 초안» 을 올린다. 그 뒤에 「다시 받아오기」 를 누르면 새로 받은
        // 값은 새 초안에 들어가므로, 확정판에는 칸 이름만 남고 값이 없다. 그러면 화면의
        // 「실제로 들어올 값」 이 전부 «—» 가 되고 「받아온 자료」 표도 사라진다 — 이 화면이
        // 존재하는 이유가 «무엇이 들어오는지 보고 고르는 것» 인데 그것만 없어진다.
        //
        // keepSamples 는 칸 이름이 완전히 같을 때만 빌려주므로, 상대가 칸을 바꿨는데 옛 값을
        // 보여 주는 일은 없다.
        return keepSamples(base, latestSampledSchema(connectorId));
    }

    /**
     * 작성 중인 가장 최근 초안.
     *
     * <p>초안은 길(intake)마다 따로 있을 수 있어 {@code findByConnectorIdAndStatus} 로는
     * 집을 수 없다 — 둘 이상이면 그 단건 조회가 예외를 던진다. 버전이 높은 것부터 훑는다.
     */
    private SourceSchema latestDraftSchema(UUID connectorId) {
        return mappingRepository.findByConnectorIdOrderByVersionDesc(connectorId).stream()
                .filter(mapping -> mapping.getStatus() == MappingStatus.DRAFT)
                .map(mapping -> restore(mapping.getSourceSchema()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * 값까지 갖고 있는 가장 최근 정의.
     *
     * <p><b>값을 빌려 오는 용도로만 쓴다.</b> 이것을 칸 목록의 출처로 삼으면 아직 값을 받아
     * 오지 않은 연동에서 화면이 통째로 비어 버린다 — 실제로 그렇게 폼이 사라져 재저장이
     * 400 으로 떨어졌다.
     */
    private SourceSchema latestSampledSchema(UUID connectorId) {
        return mappingRepository.findByConnectorIdOrderByVersionDesc(connectorId).stream()
                .map(mapping -> restore(mapping.getSourceSchema()))
                .filter(Objects::nonNull)
                .filter(schema -> !schema.sampleRows().isEmpty())
                .findFirst()
                .orElse(null);
    }

    /**
     * 받아온 것을 담아 둔다. 「다시 받아오기」가 이것을 부른다.
     *
     * <p>매핑을 아직 저장하지 않았어도 담아 둔다 — 그래야 다음에 화면을 열 때 상대를 부르지
     * 않는다.
     */
    @Transactional
    public void rememberSchema(UUID connectorId, SourceSchema schema) {
        rememberSchema(connectorId, Intake.AUTO, schema);
    }

    @Transactional
    public void rememberSchema(UUID connectorId, Intake intake, SourceSchema schema) {
        Connector connector = connector(connectorId);
        ConnectorMapping draft = draftFor(connector, intake);
        draft.replaceSchema(snapshotOf(schema));
        mappingRepository.save(draft);
    }

    @Transactional
    public ConnectorMapping saveDraft(UUID connectorId, SourceSchema schema,
                                      List<FieldMappingForm> forms) {
        return saveDraft(connectorId, Intake.AUTO, schema, forms);
    }

    @Transactional
    public ConnectorMapping saveDraft(UUID connectorId, Intake intake, SourceSchema schema,
                                      List<FieldMappingForm> forms) {
        Connector connector = connector(connectorId);
        ConnectorMapping draft = draftFor(connector, intake);

        // 저장할 때 화면은 칸 «이름» 만 돌려보낸다. 그것으로 담아 둔 것을 통째로 덮으면
        // 실제로 들어올 값이 사라져, 저장 한 번에 미리보기가 전부 «—» 가 된다. 사람은 그때
        // 자기가 뭘 지웠는지 모른다. 이름만 왔으면 값은 갖고 있던 것을 지킨다.
        draft.replaceSchema(snapshotOf(keepSamples(schema, restore(draft.getSourceSchema()))));
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
        return activate(connectorId, Intake.AUTO);
    }

    @Transactional
    public ConnectorMapping activate(UUID connectorId, Intake intake) {
        ConnectorMapping draft = mappingRepository
                .findByConnectorIdAndIntakeOrderByVersionDesc(connectorId, intake).stream()
                .filter(candidate -> candidate.getStatus() == MappingStatus.DRAFT)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.MAPPING_NOT_FOUND));

        // 이을 칸이 하나도 없는 초안은 확정하지 않는다.
        //
        // 확정은 되돌리기 어렵다 — 쓰던 확정판이 내려가고 이것이 그 자리에 올라간다. 빈
        // 초안을 올리면 그 뒤의 모든 적재가 「필수 칸이 비었다」 로 전 행 실패하는데, 확정
        // 자체는 정상적으로 끝난 것처럼 보여 원인을 찾기 어렵다.
        //
        // 빈 초안은 사람이 만든 것이 아닐 수 있다. 「다시 받아오기」 가 저장된 초안이 없으면
        // 빈 초안을 새로 만들기 때문에, 확정 직후 칸 구조만 갱신해도 이 상태가 된다.
        if (fieldMapRepository.findByMappingIdOrderBySortOrder(draft.getId()).isEmpty()) {
            throw new BusinessException(ErrorCode.MAPPING_EMPTY);
        }

        mappingRepository
                .findByConnectorIdAndIntakeAndStatus(connectorId, intake, MappingStatus.ACTIVE)
                .ifPresent(active -> {
                    active.archive();
                    mappingRepository.saveAndFlush(active);
                });

        draft.activate();
        ConnectorMapping activated = mappingRepository.save(draft);

        // 확정한 판단만 기억한다. 화면에서 고르는 중에 기억하면 고민하며 눌러 본 것까지
        // 학습해 기억이 오염되고, 그 뒤로 잘못된 추천이 계속 나온다.
        rememberConnections(connectorId, activated);
        return activated;
    }

    /**
     * 이번에 사람이 내린 연결 판단을 남긴다.
     *
     * <p>다음에 같은 칸 이름이 오면 시스템이 먼저 골라 준다 — 우리가 모르는 시스템도 <b>한 번만
     * 손대면 그 뒤로는 자동</b>이 된다.
     */
    private void rememberConnections(UUID connectorId, ConnectorMapping mapping) {
        Connector connector = connector(connectorId);
        String modelCode = targetModelRepository.findById(connector.getTargetModelId())
                .map(TargetModel::getCode)
                .orElse(null);
        if (modelCode == null) {
            return;
        }
        Map<String, String> connections = new LinkedHashMap<>();
        fieldMapRepository.findByMappingIdOrderBySortOrder(mapping.getId())
                .forEach(map -> connections.put(map.getSourceField(), map.getTargetFieldKey()));
        memories.remember(modelCode, connections);
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
