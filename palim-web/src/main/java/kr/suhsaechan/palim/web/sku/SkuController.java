package kr.suhsaechan.palim.web.sku;

import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 재고 관리 화면 (F-03, F-05).
 *
 * <p>도입 시 SKU 를 등록하고 초기 재고를 입력하는 화면이다. 실물 실사 기준으로 1회 입력한다.
 *
 * <p>재고 변동은 사유별로 경로를 나눈다 — 입고·폐기·실사 조정이 이력에 서로 다른 사유로
 * 기록되어야 나중에 추적이 가능하다.
 *
 * @deprecated 재고 시스템 동결(07-DECISIONS 023). 내비게이션에서 제거되었고 수정하지 않는다.
 */
@Deprecated
@Slf4j
@Controller
@RequiredArgsConstructor
public class SkuController {

    private static final String REDIRECT_LIST = "redirect:/skus";

    private final SkuAdminService skuAdminService;
    private final ErrorMessageResolver errorMessageResolver;

    @GetMapping("/skus")
    public String list(Model model) {
        model.addAttribute("title", "재고 관리");
        model.addAttribute("skus", skuAdminService.findAll());
        return "sku/list";
    }

    @GetMapping("/skus/{skuId}")
    public String detail(@PathVariable UUID skuId, Model model) {
        SkuView sku = skuAdminService.find(skuId);
        model.addAttribute("title", "%s %s".formatted(sku.code(), sku.name()));
        model.addAttribute("sku", sku);
        model.addAttribute("movements", skuAdminService.findMovements(skuId));
        return "sku/detail";
    }

    @PostMapping("/skus")
    public String register(@RequestParam String code,
                           @RequestParam String name,
                           @RequestParam int initialQuantity,
                           @RequestParam int safetyThreshold,
                           RedirectAttributes redirectAttributes) {
        try {
            skuAdminService.register(code, name, initialQuantity, safetyThreshold);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "SKU %s 를 등록했습니다.".formatted(code));
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT_LIST;
    }

    @PostMapping("/skus/{skuId}/restock")
    public String restock(@PathVariable UUID skuId,
                          @RequestParam int quantity,
                          @RequestParam(required = false) String memo,
                          RedirectAttributes redirectAttributes) {
        try {
            skuAdminService.restock(skuId, quantity, defaultMemo(memo, "입고"));
            redirectAttributes.addFlashAttribute("flashSuccess", "%d개 입고 처리했습니다.".formatted(quantity));
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return redirectToDetail(skuId);
    }

    @PostMapping("/skus/{skuId}/dispose")
    public String dispose(@PathVariable UUID skuId,
                          @RequestParam int quantity,
                          @RequestParam(required = false) String memo,
                          RedirectAttributes redirectAttributes) {
        try {
            skuAdminService.dispose(skuId, quantity, defaultMemo(memo, "폐기·분실"));
            redirectAttributes.addFlashAttribute("flashSuccess", "%d개 차감했습니다.".formatted(quantity));
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return redirectToDetail(skuId);
    }

    /**
     * 실사 조정.
     *
     * <p>절대값으로 덮어쓴다. 이력에는 변경 전후의 <b>차이</b>가 기록되어 누적합 대조가 유지된다.
     */
    @PostMapping("/skus/{skuId}/adjust")
    public String adjust(@PathVariable UUID skuId,
                         @RequestParam int newQuantity,
                         @RequestParam(required = false) String memo,
                         RedirectAttributes redirectAttributes) {
        try {
            skuAdminService.adjust(skuId, newQuantity, defaultMemo(memo, "실사 조정"));
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "재고를 %d개로 조정했습니다.".formatted(newQuantity));
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return redirectToDetail(skuId);
    }

    @PostMapping("/skus/{skuId}/threshold")
    public String changeThreshold(@PathVariable UUID skuId,
                                  @RequestParam int threshold,
                                  RedirectAttributes redirectAttributes) {
        try {
            skuAdminService.changeSafetyThreshold(skuId, threshold);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "안전재고 임계치를 %d개로 변경했습니다.".formatted(threshold));
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return redirectToDetail(skuId);
    }

    @PostMapping("/skus/{skuId}/discontinue")
    public String discontinue(@PathVariable UUID skuId, RedirectAttributes redirectAttributes) {
        try {
            skuAdminService.discontinue(skuId);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "단종 처리했습니다. 재고 이력은 보존됩니다.");
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT_LIST;
    }

    private static String defaultMemo(String memo, String fallback) {
        return memo == null || memo.isBlank() ? fallback : memo.trim();
    }

    private static String redirectToDetail(UUID skuId) {
        return "redirect:/skus/" + skuId;
    }

    private void addError(RedirectAttributes redirectAttributes, BusinessException exception) {
        log.warn("재고 작업 실패 — {}", exception.getErrorCode().name());
        redirectAttributes.addFlashAttribute("flashError",
                errorMessageResolver.resolve(exception.getErrorCode(), exception.messageArgs()));
    }
}
