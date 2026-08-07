package kr.suhsaechan.palim.web.mapping;

import java.util.UUID;
import kr.suhsaechan.palim.common.ChannelCode;
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
 * 상품 매핑 화면 (F-04).
 *
 * <p>매핑되지 않은 상품의 주문은 재고에 반영되지 않는다. 이 화면이 없으면 <b>재고가 조용히
 * 틀어진 상태를 고칠 방법이 없다.</b>
 *
 * @deprecated 재고 시스템 동결(07-DECISIONS 023). 내비게이션에서 제거되었고 수정하지 않는다.
 */
@Deprecated
@Slf4j
@Controller
@RequiredArgsConstructor
public class MappingController {

    private static final String REDIRECT = "redirect:/mappings";

    private final MappingAdminService mappingAdminService;
    private final ErrorMessageResolver errorMessageResolver;

    @GetMapping("/mappings")
    public String list(@RequestParam(required = false) ChannelCode channel, Model model) {
        ChannelCode selected = channel != null ? channel : ChannelCode.COUPANG;

        model.addAttribute("title", "상품 매핑");
        model.addAttribute("channels", ChannelCode.values());
        model.addAttribute("selectedChannel", selected);
        model.addAttribute("mappings", mappingAdminService.findByChannel(selected));
        model.addAttribute("unmappedLines", mappingAdminService.findUnmappedLines());
        model.addAttribute("skus", mappingAdminService.findSelectableSkus());
        return "mapping/list";
    }

    /**
     * 매핑을 등록하고 소급 반영 결과를 알린다.
     *
     * <p>소급 반영 건수를 보여주는 이유는, 발주자가 "매핑했는데 재고가 안 빠졌다"고 오해하지
     * 않게 하기 위함이다.
     */
    @PostMapping("/mappings")
    public String connect(@RequestParam ChannelCode channelCode,
                          @RequestParam String channelProductNo,
                          @RequestParam(required = false) String channelOptionNo,
                          @RequestParam String channelProductName,
                          @RequestParam UUID skuId,
                          RedirectAttributes redirectAttributes) {
        try {
            int applied = mappingAdminService.connect(channelCode, channelProductNo.trim(),
                    channelOptionNo, channelProductName.trim(), skuId);

            redirectAttributes.addFlashAttribute("flashSuccess", applied > 0
                    ? "매핑을 등록하고 미반영 주문 %d건의 재고를 반영했습니다.".formatted(applied)
                    : "매핑을 등록했습니다. 소급 반영할 주문은 없습니다.");
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT + "?channel=" + channelCode;
    }

    @PostMapping("/mappings/{mappingId}/reconnect")
    public String reconnect(@PathVariable UUID mappingId,
                            @RequestParam UUID skuId,
                            RedirectAttributes redirectAttributes) {
        try {
            mappingAdminService.reconnect(mappingId, skuId);
            redirectAttributes.addFlashAttribute("flashSuccess", "매핑 대상을 변경했습니다.");
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT;
    }

    @PostMapping("/mappings/{mappingId}/deactivate")
    public String deactivate(@PathVariable UUID mappingId, RedirectAttributes redirectAttributes) {
        try {
            mappingAdminService.deactivate(mappingId);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "매핑을 해제했습니다. 이후 주문은 미매핑으로 저장됩니다.");
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT;
    }

    @PostMapping("/mappings/{mappingId}/activate")
    public String activate(@PathVariable UUID mappingId, RedirectAttributes redirectAttributes) {
        try {
            mappingAdminService.activate(mappingId);
            redirectAttributes.addFlashAttribute("flashSuccess", "매핑을 다시 사용합니다.");
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT;
    }

    private void addError(RedirectAttributes redirectAttributes, BusinessException exception) {
        log.warn("매핑 작업 실패 — {}", exception.getErrorCode().name());
        redirectAttributes.addFlashAttribute("flashError",
                errorMessageResolver.resolve(exception.getErrorCode(), exception.messageArgs()));
    }
}
