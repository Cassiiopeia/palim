package kr.suhsaechan.palim.web.connector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMap;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMapRepository;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.MappingStatus;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetField;
import kr.suhsaechan.palim.connector.run.ConnectorRun;
import kr.suhsaechan.palim.connector.run.ConnectorRunner;
import kr.suhsaechan.palim.connector.run.RollbackService;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.run.RunRequest;
import kr.suhsaechan.palim.connector.run.RunTrigger;
import kr.suhsaechan.palim.connector.source.SourceSchema;
import kr.suhsaechan.palim.connector.transform.TransformType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import kr.suhsaechan.palim.connector.define.Intake;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 연동 커넥터 화면 (#55).
 *
 * <p>연동 정의를 데이터로 둔 이유가 "화면에서 만들기 위해서"다. 이 화면이 없으면 Phase 1 의
 * 엔진을 사람이 쓸 수 없다.
 *
 * <p><b>AI 없이 전 과정이 완주된다.</b> 헤더에서 필드 목록을 뽑는 것은 코드가 하고, 연결은
 * 사람이 한다. AI 초안은 나중에 붙는 <b>보조</b> 기능이다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ConnectorController {

    private static final int RUN_HISTORY_LIMIT = 30;
    private static final int ERROR_ROW_LIMIT = 100;
    /** 시험 실행 결과에 보여줄 줄 수. 몇 줄만 보여주면 확인이 형식이 된다. */
    private static final int PREVIEW_LIMIT = 200;

    private final ConnectorAdminService adminService;
    private final ConnectorQueryService queryService;
    private final ConnectorRunner runner;
    private final RollbackService rollbackService;
    private final ErrorMessageResolver errorMessages;
    private final MappingViewAssembler assembler;
    private final PostScriptAdminService postScripts;
    private final ConnectorRemovalService removal;
    private final ConnectorMappingRepository mappings;
    private final ConnectorFieldMapRepository fieldMaps;

    @GetMapping("/connectors")
    public String list(Model model) {
        model.addAttribute("title", "재고 가져오는 곳");
        model.addAttribute("connectors",
                queryService.list(ConnectorAdminService.DEFAULT_TENANT));
        return "connector/list";
    }

    @GetMapping("/connectors/new")
    public String createForm(Model model) {
        model.addAttribute("title", "연동 만들기");
        model.addAttribute("targetModels", adminService.targetModels());
        model.addAttribute("sourceTypes", SourceType.values());
        return "connector/new";
    }

    @PostMapping("/connectors")
    public String create(@RequestParam String code, @RequestParam String name,
                         @RequestParam UUID targetModelId, @RequestParam SourceType sourceType,
                         @RequestParam(defaultValue = "EA") String defaultUnit,
                         RedirectAttributes redirect) {
        try {
            Connector connector = adminService.create(code, name, targetModelId, sourceType,
                    defaultUnit);
            redirect.addFlashAttribute("flashSuccess", "연동을 만들었습니다. 원천 파일을 올려 매핑을 정의하세요.");
            return "redirect:/connectors/" + connector.getId() + "/mapping";
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
            return "redirect:/connectors/new";
        }
    }

    /**
     * 매핑 편집기.
     *
     * <p>파일을 올리기 전에는 왼쪽(원천 필드)이 비어 있다. 목표 필드는 항상 보여준다 —
     * 무엇을 채워야 하는지 먼저 알아야 어떤 파일을 올릴지 판단할 수 있다.
     */
    /**
     * 시험이 쓰는 판과 적재가 쓰는 판이 같은가.
     *
     * <p>둘은 일부러 다르다(초안으로 시험, 확정판으로 적재). 화면이 그 차이를 말하지 않으면
     * 「시험은 됐는데 적재만 실패」 가 되고, 사람은 같은 화면에서 같은 버튼을 눌렀으므로
     * 무엇이 달랐는지 알 길이 없다.
     */
    private MappingStateView mappingState(UUID connectorId) {
        Integer active = mappings
                .findByConnectorIdAndStatus(connectorId, MappingStatus.ACTIVE)
                .map(ConnectorMapping::getVersion).orElse(null);
        ConnectorMapping latest = mappings
                .findFirstByConnectorIdOrderByVersionDesc(connectorId).orElse(null);
        // 「칸이 있는가」 로 «사람이 만든 초안» 과 «다시 받아오기가 만든 빈 뼈대» 를 가른다.
        boolean hasFields = latest != null
                && !fieldMaps.findByMappingIdOrderBySortOrder(latest.getId()).isEmpty();
        return new MappingStateView(active,
                latest == null ? null : latest.getVersion(), hasFields);
    }

    @GetMapping("/connectors/{id}/mapping")
    public String mapping(@PathVariable UUID id,
                          @RequestParam(required = false) String intake,
                          Model model) {
        Connector connector = adminService.connector(id);
        // 엑셀 열 이름과 API 칸 이름이 달라 칸 맞추기를 길마다 따로 둔다. 어느 길을 맞추고
        // 있는지 화면이 말하지 않으면 사람은 자기가 무엇을 고치는지 모른다.
        Intake using = Intake.of(intake);
        model.addAttribute("title", connector.getName() + " · 칸 맞추기");
        model.addAttribute("intake", using);
        model.addAttribute("connector", connector);
        model.addAttribute("targetFields", adminService.targetFields(id));
        model.addAttribute("transformTypes", TransformType.values());
        model.addAttribute("existing", existingByTarget(id));
        model.addAttribute("fetchesItself", adminService.fetchesItself(connector));
        // 담기 직전에 값을 다듬는 스크립트들. 꺼 둔 것도 보여야 켜고 끌 수 있다.
        model.addAttribute("postScripts", postScripts.active(id));
        model.addAttribute("mappingState", mappingState(id));

        // 담아 둔 것을 먼저 쓴다.
        //
        // 화면을 열 때마다 상대를 부르면 새로고침 한 번이 원격 호출 한 번이 되고, 상대가 잠깐
        // 삐끗하면 화면 자체가 안 열린다 — 이미 저장해 둔 칸 연결조차 못 보게 된다. 실제로
        // 그렇게 500 이 났다. 칸 구조는 자주 바뀌는 것이 아니므로, 갱신은 「다시 받아오기」를
        // 누를 때만 한다.
        SourceSchema schema = adminService.storedSchema(id);
        if (schema == null && adminService.fetchesItself(connector)) {
            // 한 번도 받아온 적이 없다면 이번엔 받아 온다. 그래야 첫 화면부터 고를 것이 보인다.
            schema = fetchSchemaQuietly(connector, model);
        }
        if (schema != null) {
            model.addAttribute("schema", schema);
        }
        addMappingView(model, id, connector, schema);
        return "connector/mapping";
    }

    /**
     * 원천에서 칸을 받아 온다. <b>실패해도 화면은 연다.</b>
     *
     * <p>원격 호출은 언제든 실패한다 — 상대 점검, 호출 제한, 네트워크. 그때마다 화면이 죽으면
     * 사장님은 무엇이 잘못됐는지조차 볼 수 없고, 이미 해 둔 칸 연결도 못 본다.
     */
    private SourceSchema fetchSchemaQuietly(Connector connector, Model model) {
        try {
            return adminService.readSchema(connector);
        } catch (BusinessException e) {
            model.addAttribute("flashError", errorMessages.resolve(e.getErrorCode(),
                    e.messageArgs()));
            return null;
        } catch (RuntimeException e) {
            // 상대가 우리가 아는 형태로 답하지 않은 경우다(HTTP 오류·응답 파싱 실패 등).
            // 사유는 로그에만 남긴다 — 화면에 스택이나 상대 서버의 오류 HTML 을 그대로
            // 내보내면 읽을 수도 없고 안전하지도 않다.
            log.warn("칸을 받아오지 못했습니다 — connector={}", connector.getCode(), e);
            model.addAttribute("flashError",
                    "지금 원천에서 칸을 받아오지 못했습니다. 「다시 받아오기」로 한 번 더 시도해 보세요.");
            return null;
        }
    }

    /**
     * 화면이 그릴 것을 채운다.
     *
     * <p>표준 항목 · 저장된 연결 · 자동 추천 · 값 미리보기를 한 덩어리로 만든다. 화면이 이
     * 셋을 각각 받아 맞춰 보게 하면 템플릿에 판단이 들어가고, 그 판단은 테스트할 수 없다.
     */
    private void addMappingView(Model model, UUID id, Connector connector, SourceSchema schema) {
        String modelCode = adminService.targetModelCode(connector);
        Map<String, ConnectorFieldMap> existing = existingByTarget(id);
        model.addAttribute("groups", assembler.assemble(
                adminService.targetFields(id), existing, schema, modelCode));
        model.addAttribute("leftovers", assembler.leftovers(schema, existing));
    }

    /**
     * 원천에서 칸을 다시 받아 온다.
     *
     * <p>화면을 열 때마다 부르지 않고 <b>누를 때만</b> 부른다. 칸 구조는 자주 바뀌지 않는데
     * 새로고침마다 상대를 부르면 하루 허용량을 갉아먹고, 상대가 삐끗하면 화면이 안 열린다.
     */
    @PostMapping("/connectors/{id}/schema/refresh")
    public String refreshSchema(@PathVariable UUID id, RedirectAttributes redirect) {
        Connector connector = adminService.connector(id);
        try {
            SourceSchema schema = adminService.readSchema(connector);
            // 「다시 받아오기」 는 스스로 가져오는 길에만 있다. 파일 길의 칸은 사람이 올린
            // 파일에서 읽으므로 여기로 오지 않는다.
            adminService.rememberSchema(id, Intake.AUTO, schema);
            redirect.addFlashAttribute("flashSuccess",
                    "칸 %d 개를 다시 받아왔습니다.".formatted(schema.fields().size()));
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        } catch (RuntimeException e) {
            log.warn("칸을 다시 받아오지 못했습니다 — connector={}", connector.getCode(), e);
            redirect.addFlashAttribute("flashError",
                    "지금 원천에서 칸을 받아오지 못했습니다. 잠시 뒤 다시 시도해 보세요.");
        }
        return "redirect:/connectors/" + id + "/mapping";
    }

    /** 파일 업로드 → 원천 필드 추출. 매핑 화면을 채워 다시 보여준다. */
    @PostMapping("/connectors/{id}/schema")
    public String readSchema(@PathVariable UUID id, @RequestParam MultipartFile file,
                             @RequestParam(required = false) String intake,
                             @RequestParam(defaultValue = "1") int headerRow,
                             Model model, RedirectAttributes redirect) {
        Connector connector = adminService.connector(id);
        Path temp = null;
        try {
            temp = adminService.saveTemporary(file);
            SourceSchema schema = adminService.readSchema(connector, temp, headerRow);
            Intake using = Intake.of(intake);
            // 읽은 칸을 «그 길의» 초안에 담아 둔다. 안 담으면 화면을 다시 열 때 파일을 또
            // 올려야 하고, 급할 때 쓰는 길에서 그 왕복이 제일 아프다.
            adminService.rememberSchema(id, using, schema);

            model.addAttribute("title", connector.getName() + " · 칸 맞추기");
            model.addAttribute("intake", using);
            model.addAttribute("connector", connector);
            model.addAttribute("targetFields", adminService.targetFields(id));
            model.addAttribute("transformTypes", TransformType.values());
            model.addAttribute("existing", existingByTarget(id));
            model.addAttribute("schema", schema);
            model.addAttribute("headerRow", headerRow);
            model.addAttribute("fetchesItself", adminService.fetchesItself(connector));
            // 담기 직전에 값을 다듬는 스크립트들. 꺼 둔 것도 보여야 켜고 끌 수 있다.
            model.addAttribute("postScripts", postScripts.active(id));
            model.addAttribute("mappingState", mappingState(id));
            addMappingView(model, id, connector, schema);
            return "connector/mapping";

        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
            return "redirect:/connectors/" + id + "/mapping?intake=" + Intake.of(intake).name();
        } finally {
            adminService.deleteQuietly(temp);
        }
    }

    /**
     * 매핑 저장.
     *
     * <p>화면이 목표 필드 수만큼 배열을 보내고, 연결하지 않은 줄은 저장하지 않는다.
     */
    @PostMapping("/connectors/{id}/mapping")
    public String saveMapping(@PathVariable UUID id,
                              @RequestParam List<String> sourceFields,
                              @RequestParam List<String> targetKeys,
                              @RequestParam(required = false) List<String> transformTypes,
                              @RequestParam(required = false) List<String> params,
                              @RequestParam List<String> schemaFields,
                              @RequestParam(required = false) String intake,
                              RedirectAttributes redirect) {
        List<FieldMappingForm> forms = new ArrayList<>();
        for (int i = 0; i < targetKeys.size(); i++) {
            forms.add(new FieldMappingForm(
                    at(sourceFields, i), at(targetKeys, i),
                    at(transformTypes, i), at(params, i), i));
        }

        Intake using = Intake.of(intake);
        adminService.saveDraft(id, using, new SourceSchema(schemaFields, List.of(), 0), forms);
        redirect.addFlashAttribute("flashSuccess",
                "칸 맞추기를 저장했습니다. 시험 실행으로 결과를 확인하세요.");
        return "redirect:/connectors/" + id + "/mapping?intake=" + using.name();
    }

    /**
     * 저장하고 곧바로 시험 실행.
     *
     * <p>고르는 화면과 실행 버튼이 <b>따로</b> 있으면, 고르기만 하고 실행해 저장된 옛 것으로
     * 돌게 된다. 실제로 그렇게 전 줄이 「필수 칸이 비었다」로 떨어졌는데, 화면에는 고른 칸이
     * 그대로 보이므로 사람은 자기가 무엇을 안 했는지 알 수 없다.
     *
     * <p>그래서 같은 폼에서 저장과 실행을 잇는다. 「지금 고른 그대로 돌려 본다」가 사람이
     * 기대하는 동작이다.
     */
    @PostMapping("/connectors/{id}/mapping/run")
    public String saveAndRun(@PathVariable UUID id,
                             @RequestParam List<String> sourceFields,
                             @RequestParam List<String> targetKeys,
                             @RequestParam(required = false) List<String> transformTypes,
                             @RequestParam(required = false) List<String> params,
                             @RequestParam List<String> schemaFields,
                             @RequestParam(required = false) String intake,
                             @RequestParam(required = false) MultipartFile file,
                             @RequestParam(defaultValue = "1") int headerRow,
                             RedirectAttributes redirect) {
        List<FieldMappingForm> forms = new ArrayList<>();
        for (int i = 0; i < targetKeys.size(); i++) {
            forms.add(new FieldMappingForm(
                    at(sourceFields, i), at(targetKeys, i),
                    at(transformTypes, i), at(params, i), i));
        }
        adminService.saveDraft(id, Intake.of(intake), new SourceSchema(schemaFields, List.of(), 0),
                forms);
        // 파일 길을 맞추는 중이면 그 파일로 시험 실행해야 한다 — API 로 돌리면 방금 맞춘 칸이
        // 아니라 다른 칸으로 도는 셈이라 결과를 믿을 수 없다.
        return run(id, file, headerRow, RunMode.TEST, false, redirect);
    }

    /**
     * 실행.
     *
     * <p>TEST 는 스테이징에만 쓰므로 운영 데이터에 닿지 않는다. LIVE 는 확정된 매핑에서만
     * 가능하며, 그렇지 않으면 엔진이 거부한다.
     */
    @PostMapping("/connectors/{id}/run")
    public String run(@PathVariable UUID id,
                      @RequestParam(required = false) MultipartFile file,
                      @RequestParam(defaultValue = "1") int headerRow,
                      @RequestParam RunMode mode,
                      @RequestParam(defaultValue = "false") boolean skipPostScripts,
                      RedirectAttributes redirect) {
        Path temp = null;
        try {
            // 스스로 가져오는 원천은 올릴 파일이 없다. 파일을 필수로 두면 API 커넥터는
            // 시험 실행조차 못 하고, 매일 자동 수집으로 넘어갈 수도 없다.
            if (file != null && !file.isEmpty()) {
                temp = adminService.saveTemporary(file);
            }
            ConnectorRun run = runner.run(new RunRequest(id, mode, RunTrigger.MANUAL, temp,
                    headerRow, skipPostScripts));

            redirect.addFlashAttribute("flashSuccess",
                    "%s 실행 완료 — 총 %d건 중 성공 %d건, 실패 %d건".formatted(
                            mode == RunMode.TEST ? "시험 실행" : "실제 적재",
                            run.getTotalCount(), run.getSuccessCount(), run.getFailedCount()));
            return "redirect:/connectors/" + id + "/runs/" + run.getId();

        } catch (BusinessException e) {
            // getMessage() 는 «API_PROBE_FAILED(K015) args=[…]» 같은 로그용 문자열이다.
            // 그대로 내보내면 사용자는 무엇을 고쳐야 하는지 알 수 없다.
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
            return "redirect:/connectors/" + id + "/mapping";
        } finally {
            adminService.deleteQuietly(temp);
        }
    }

    /**
     * 연결 확정.
     *
     * <p>확정할 초안이 없을 때 「매핑 버전을 찾을 수 없습니다」 라고만 하면 사람은 <b>무엇을
     * 해야 하는지 알 수 없다.</b> 이 화면에서 그 상황은 둘 뿐이고 할 일도 서로 다르다 —
     * 아직 저장하지 않았거나, 이미 확정해 둔 것이다. 둘을 갈라 말한다.
     */
    /**
     * 파일을 어디서 받는지 고친다.
     *
     * <p>상대 사이트는 언젠가 바뀐다. 그때 사람이 그 자리에서 고쳐 두면 <b>다음부터 그게
     * 정답</b>이 된다 — 코드를 고쳐야 하면 그 사이 아무도 못 쓴다.
     */
    /** 프리셋이 아는 기본 안내를 지금 넣는다. 연결을 저장하기 전에 만든 연동은 비어 있다. */
    @PostMapping("/connectors/{id}/file-guide/seed")
    public String seedFileGuide(@PathVariable UUID id, RedirectAttributes redirect) {
        String guide = adminService.seedFileGuide(id);
        redirect.addFlashAttribute(guide.isBlank() ? "flashError" : "flashSuccess",
                guide.isBlank()
                        ? "넣을 기본 안내가 없습니다. 직접 적어 주세요."
                        : "기본 안내를 넣었습니다. 실제 메뉴 이름을 확인해 고쳐 두세요.");
        return "redirect:/connectors/" + id;
    }

    @PostMapping("/connectors/{id}/file-guide")
    public String changeFileGuide(@PathVariable UUID id,
                                  @RequestParam(required = false) String guide,
                                  RedirectAttributes redirect) {
        adminService.changeFileGuide(id, guide);
        redirect.addFlashAttribute("flashSuccess", "받는 방법 안내를 고쳤습니다.");
        return "redirect:/connectors/" + id;
    }

    @PostMapping("/connectors/{id}/activate")
    public String activate(@PathVariable UUID id,
                           @RequestParam(required = false) String intake,
                           RedirectAttributes redirect) {
        try {
            adminService.activate(id, Intake.of(intake));
            redirect.addFlashAttribute("flashSuccess",
                    "매핑을 확정했습니다. 이제 실제 적재가 가능합니다.");
        } catch (BusinessException e) {
            if (e.is(ErrorCode.MAPPING_NOT_FOUND)) {
                redirect.addFlashAttribute("flashError", adminService.hasActiveMapping(id)
                        ? "이미 확정되어 있습니다. 바꾸려면 칸을 고친 뒤 「연결 저장」 을 먼저 누르세요."
                        : "확정할 것이 없습니다. 칸을 고른 뒤 「연결 저장」 을 먼저 누르세요.");
            } else {
                redirect.addFlashAttribute("flashError",
                        errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
            }
        }
        return "redirect:/connectors/" + id + "/mapping";
    }

    /**
     * 연동 지우기.
     *
     * <p>자료를 담은 적이 있거나 대조가 쓰고 있으면 <b>막고 이유를 말한다.</b> DB 에 외래키가
     * 없어 지우는 것 자체는 아무것도 막아주지 않는다 — 그대로 지우면 대조가 다음 날 아침
     * 조용히 깨지고, 화면에는 「비교할 재고가 없습니다」 만 떠서 원인을 알 수 없다.
     */
    @PostMapping("/connectors/{id}/remove")
    public String remove(@PathVariable UUID id, RedirectAttributes redirect) {
        String blocked = removal.blockedReason(id);
        if (blocked != null) {
            redirect.addFlashAttribute("flashError", blocked);
            return "redirect:/connectors";
        }
        removal.remove(id);
        redirect.addFlashAttribute("flashSuccess", "연동을 지웠습니다.");
        return "redirect:/connectors";
    }

    /** 끄고 켜기. 수집은 멈추되 담긴 자료와 대조 정의는 그대로 산다. */
    @PostMapping("/connectors/{id}/enabled")
    public String changeEnabled(@PathVariable UUID id,
                                @RequestParam(defaultValue = "false") boolean enabled,
                                RedirectAttributes redirect) {
        removal.changeEnabled(id, enabled);
        redirect.addFlashAttribute("flashSuccess",
                enabled ? "다시 켰습니다. 정해 둔 시각에 수집합니다."
                        : "껐습니다. 담긴 자료와 대조 정의는 그대로 남습니다.");
        return "redirect:/connectors";
    }

    @GetMapping("/connectors/{id}/runs")
    public String runs(@PathVariable UUID id, Model model) {
        Connector connector = adminService.connector(id);
        model.addAttribute("title", connector.getName() + " · 실행 이력");
        model.addAttribute("connector", connector);
        model.addAttribute("runs", queryService.runs(id, RUN_HISTORY_LIMIT));
        return "connector/runs";
    }

    /** 실행 상세 — 실패 행과 테스트 적재 결과를 함께 보여준다. */
    @GetMapping("/connectors/{id}/runs/{runId}")
    public String runDetail(@PathVariable UUID id, @PathVariable UUID runId, Model model) {
        Connector connector = adminService.connector(id);
        model.addAttribute("title", connector.getName() + " · 실행 결과");
        model.addAttribute("connector", connector);
        model.addAttribute("run", queryService.runs(id, RUN_HISTORY_LIMIT).stream()
                .filter(summary -> summary.id().equals(runId))
                .findFirst().orElse(null));
        // 실패한 줄을 하나씩 늘어놓으면 같은 원인이 수십 번 반복되고, 그 옆에 칸 스무 개짜리
        // 원문이 붙는다. 사람이 알아야 하는 것은 «무엇이 왜 안 됐고 어디를 고치면 되는지» 다.
        Map<String, String> labels = new LinkedHashMap<>();
        adminService.targetFields(id)
                .forEach(field -> labels.put(field.getFieldKey(), field.getDisplayName()));
        Map<String, String> sourceOf = new LinkedHashMap<>();
        existingByTarget(id).forEach((target, map) -> sourceOf.put(target, map.getSourceField()));
        model.addAttribute("errorGroups", RunErrorGroupView.of(
                queryService.errors(runId, ERROR_ROW_LIMIT), labels, sourceOf));

        // JSON 원문을 그대로 뿌리면 값이 제대로 들어갔는지 사람이 중괄호를 읽어야 한다.
        // 확인하라고 만든 화면인데 확인할 수 없으면 그 단계는 형식이 된다.
        // 화면은 담긴 값을 그대로 보여준다. 읽기 좋게 만드는 일은 담을 때 끝낸다.
        model.addAttribute("staging",
                StagingTableView.of(queryService.staging(runId, PREVIEW_LIMIT)));
        return "connector/run-detail";
    }

    @PostMapping("/connectors/{id}/runs/{runId}/rollback")
    public String rollback(@PathVariable UUID id, @PathVariable UUID runId,
                           RedirectAttributes redirect) {
        try {
            rollbackService.rollback(runId);
            redirect.addFlashAttribute("flashSuccess", "실행을 되돌렸습니다.");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return "redirect:/connectors/" + id + "/runs";
    }

    /** 목표 필드 키 → 현재 연결된 원천 필드. 편집기가 기존 선택을 되살린다. */
    private Map<String, ConnectorFieldMap> existingByTarget(UUID connectorId) {
        Map<String, ConnectorFieldMap> byTarget = new LinkedHashMap<>();
        adminService.currentFieldMaps(connectorId)
                .forEach(map -> byTarget.put(map.getTargetFieldKey(), map));
        return byTarget;
    }

    private String at(List<String> values, int index) {
        return values != null && index < values.size() ? values.get(index) : null;
    }

    /** 화면이 목표 필드 목록을 그릴 때 쓰는 헬퍼. */
    public record TargetFieldView(TargetField field, String sourceField) {
    }
}
