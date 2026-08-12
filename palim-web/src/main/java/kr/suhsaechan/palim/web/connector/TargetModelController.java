package kr.suhsaechan.palim.web.connector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.model.FieldDataType;
import kr.suhsaechan.palim.connector.model.TargetField;
import kr.suhsaechan.palim.connector.model.TargetFieldRepository;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 목표 모델 관리 화면 (#55).
 *
 * <p>기본 제공 모델은 조회만 한다. 커스텀 모델은 만들 수 있지만 <b>그 위에 도메인 기능이
 * 자동으로 붙지는 않는다</b> — 수집·변환·저장·조회까지이고, 판단 로직이 필요해지면 기본 제공
 * 모델로 승격시킨다. 이 경계를 화면에서도 분명히 보여준다.
 */
@Controller
@RequiredArgsConstructor
public class TargetModelController {

    private final TargetModelRepository modelRepository;
    private final TargetFieldRepository fieldRepository;

    @GetMapping("/connectors/models")
    @Transactional(readOnly = true)
    public String list(Model model) {
        List<TargetModel> models =
                modelRepository.findByTenantIdOrderByCode(ConnectorAdminService.DEFAULT_TENANT);

        Map<UUID, Integer> fieldCounts = new LinkedHashMap<>();
        models.forEach(target -> fieldCounts.put(target.getId(),
                fieldRepository.findByTargetModelIdOrderBySortOrder(target.getId()).size()));

        model.addAttribute("title", "목표 모델");
        model.addAttribute("models", models);
        model.addAttribute("fieldCounts", fieldCounts);
        return "connector/models";
    }

    @GetMapping("/connectors/models/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable UUID id, Model model) {
        TargetModel target = modelRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTOR_NOT_FOUND));

        model.addAttribute("title", target.getName() + " · 필드");
        model.addAttribute("model", target);
        model.addAttribute("fields", fieldRepository.findByTargetModelIdOrderBySortOrder(id));
        model.addAttribute("dataTypes", FieldDataType.values());
        return "connector/model-detail";
    }

    /**
     * 커스텀 모델 생성.
     *
     * <p>자연키를 반드시 받는다. "무엇이 같으면 같은 행인가"가 없으면 재실행이 매번 중복 행을
     * 만들고, 그 상태로는 자동화를 켤 수 없다.
     */
    @PostMapping("/connectors/models")
    public String create(@RequestParam String code, @RequestParam String name,
                         @RequestParam String naturalKeys, RedirectAttributes redirect) {
        try {
            List<String> keys = List.of(naturalKeys.split(",")).stream()
                    .map(String::trim).filter(key -> !key.isEmpty()).toList();
            if (keys.isEmpty()) {
                throw new BusinessException(ErrorCode.NATURAL_KEY_INCOMPLETE, "(입력 없음)");
            }
            modelRepository.save(TargetModel.custom(ConnectorAdminService.DEFAULT_TENANT,
                    code.trim(), name.trim(), keys));
            redirect.addFlashAttribute("flashSuccess",
                    "커스텀 모델을 만들었습니다. 필드를 추가하세요.");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/connectors/models";
    }

    @PostMapping("/connectors/models/{id}/fields")
    @Transactional
    public String addField(@PathVariable UUID id, @RequestParam String fieldKey,
                           @RequestParam String displayName, @RequestParam FieldDataType dataType,
                           @RequestParam(defaultValue = "false") boolean required,
                           RedirectAttributes redirect) {
        TargetModel target = modelRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTOR_NOT_FOUND));

        if (fieldRepository.existsByTargetModelIdAndFieldKey(id, fieldKey.trim())) {
            redirect.addFlashAttribute("flashError", "이미 있는 필드입니다: " + fieldKey);
            return "redirect:/connectors/models/" + id;
        }

        int order = fieldRepository.findByTargetModelIdOrderBySortOrder(id).size();
        fieldRepository.save(TargetField.of(target.getTenantId(), id, fieldKey.trim(),
                displayName.trim(), dataType, required, null, order));

        redirect.addFlashAttribute("flashSuccess", "필드를 추가했습니다.");
        return "redirect:/connectors/models/" + id;
    }
}
