package kr.suhsaechan.palim.web.connector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMap;
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
    private static final int PREVIEW_LIMIT = 20;

    private final ConnectorAdminService adminService;
    private final ConnectorQueryService queryService;
    private final ConnectorRunner runner;
    private final RollbackService rollbackService;
    private final ErrorMessageResolver errorMessages;
    private final MappingViewAssembler assembler;

    @GetMapping("/connectors")
    public String list(Model model) {
        model.addAttribute("title", "연동 커넥터");
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
    @GetMapping("/connectors/{id}/mapping")
    public String mapping(@PathVariable UUID id, Model model) {
        Connector connector = adminService.connector(id);
        model.addAttribute("title", connector.getName() + " · 매핑");
        model.addAttribute("connector", connector);
        model.addAttribute("targetFields", adminService.targetFields(id));
        model.addAttribute("transformTypes", TransformType.values());
        model.addAttribute("existing", existingByTarget(id));
        model.addAttribute("fetchesItself", adminService.fetchesItself(connector));

        // 스스로 가져오는 원천은 칸 목록을 이미 알 수 있다. 파일을 올리라고 할 이유가 없다.
        SourceSchema schema = null;
        if (adminService.fetchesItself(connector)) {
            try {
                schema = adminService.readSchema(connector);
                model.addAttribute("schema", schema);
            } catch (BusinessException e) {
                // 원천에 닿지 못해도 화면은 열려야 한다. 무엇이 막혔는지 보여주고, 사용자가
                // 연결 설정을 고치러 갈 수 있어야 한다.
                model.addAttribute("flashError", errorMessages.resolve(e.getErrorCode(),
                        e.messageArgs()));
            }
        }
        addMappingView(model, id, connector, schema);
        return "connector/mapping";
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

    /** 파일 업로드 → 원천 필드 추출. 매핑 화면을 채워 다시 보여준다. */
    @PostMapping("/connectors/{id}/schema")
    public String readSchema(@PathVariable UUID id, @RequestParam MultipartFile file,
                             @RequestParam(defaultValue = "1") int headerRow,
                             Model model, RedirectAttributes redirect) {
        Connector connector = adminService.connector(id);
        Path temp = null;
        try {
            temp = adminService.saveTemporary(file);
            SourceSchema schema = adminService.readSchema(connector, temp, headerRow);

            model.addAttribute("title", connector.getName() + " · 매핑");
            model.addAttribute("connector", connector);
            model.addAttribute("targetFields", adminService.targetFields(id));
            model.addAttribute("transformTypes", TransformType.values());
            model.addAttribute("existing", existingByTarget(id));
            model.addAttribute("schema", schema);
            model.addAttribute("headerRow", headerRow);
            model.addAttribute("fetchesItself", adminService.fetchesItself(connector));
            addMappingView(model, id, connector, schema);
            return "connector/mapping";

        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
            return "redirect:/connectors/" + id + "/mapping";
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
                              RedirectAttributes redirect) {
        List<FieldMappingForm> forms = new ArrayList<>();
        for (int i = 0; i < targetKeys.size(); i++) {
            forms.add(new FieldMappingForm(
                    at(sourceFields, i), at(targetKeys, i),
                    at(transformTypes, i), at(params, i), i));
        }

        adminService.saveDraft(id, new SourceSchema(schemaFields, List.of(), 0), forms);
        redirect.addFlashAttribute("flashSuccess",
                "매핑을 저장했습니다. 테스트 실행으로 결과를 확인하세요.");
        return "redirect:/connectors/" + id + "/mapping";
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
                      @RequestParam RunMode mode, RedirectAttributes redirect) {
        Path temp = null;
        try {
            // 스스로 가져오는 원천은 올릴 파일이 없다. 파일을 필수로 두면 API 커넥터는
            // 시험 실행조차 못 하고, 매일 자동 수집으로 넘어갈 수도 없다.
            if (file != null && !file.isEmpty()) {
                temp = adminService.saveTemporary(file);
            }
            ConnectorRun run = runner.run(new RunRequest(id, mode, RunTrigger.MANUAL, temp,
                    headerRow));

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

    @PostMapping("/connectors/{id}/activate")
    public String activate(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            adminService.activate(id);
            redirect.addFlashAttribute("flashSuccess",
                    "매핑을 확정했습니다. 이제 실제 적재가 가능합니다.");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return "redirect:/connectors/" + id + "/mapping";
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
        model.addAttribute("errors", queryService.errors(runId, ERROR_ROW_LIMIT));

        // JSON 원문을 그대로 뿌리면 값이 제대로 들어갔는지 사람이 중괄호를 읽어야 한다.
        // 확인하라고 만든 화면인데 확인할 수 없으면 그 단계는 형식이 된다.
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
