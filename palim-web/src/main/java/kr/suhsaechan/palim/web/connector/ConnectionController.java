package kr.suhsaechan.palim.web.connector;

import java.time.LocalDateTime;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.connector.source.http.ApiAuthPreset;
import kr.suhsaechan.palim.connector.source.http.ApiProbeRegistry;
import kr.suhsaechan.palim.connector.source.http.ConnectionSuggestion;
import kr.suhsaechan.palim.connector.source.http.ProbeReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 외부 시스템 연결 화면.
 *
 * <p>인증정보를 넣고 <b>한 번에 전 단계를 검증</b>한 뒤, 통과한 설정을 커넥터로 저장한다.
 * 단계를 나누지 않는 이유는 <b>어디서 막혔는지가 곧 원인</b>이기 때문이다 — 지역 조회·로그인·
 * 조회를 따로 누르게 하면 사람이 세 번 기다리고도 세 결과를 스스로 이어 붙여야 한다.
 *
 * <p>입력한 비밀값은 화면으로 되돌리지 않는다. 저장 후에는 등록 여부만 보인다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ConnectionController {

    private final ApiProbeRegistry probes;
    private final ConnectionAdminService connectionService;
    private final ErrorMessageResolver errorMessages;

    /**
     * 연결 화면.
     *
     * <p>고른 시스템을 <b>주소로 받아 서버가 화면을 다시 그린다.</b> 예전에는 화면 안의
     * 스크립트가 문구만 갈아 끼웠는데, 이 서비스의 보안 정책은 화면에 박힌 스크립트를 실행하지
     * 않는다. 그래서 시스템을 바꿔도 <b>한 글자도 바뀌지 않았다</b> — 물류 시스템을 골라도
     * 「API 인증키」를 넣으라고 했다. 화면 절반은 서버가 그리고 절반은 스크립트가 고치는 구조는
     * 스크립트가 막히는 순간 이렇게 어긋난다. 서버가 전부 그리게 두면 어긋날 곳이 없다.
     *
     * <p>고르는 동작으로 상대 시스템을 부르지 않는다. 이 요청은 화면만 다시 그린다.
     */
    @GetMapping("/connectors/connect")
    public String form(@RequestParam(required = false) ApiAuthPreset preset, Model model) {
        ConnectionForm form = new ConnectionForm();
        if (preset != null) {
            form.setPreset(preset);
            // 키 단계가 없는 시스템에 테스트/정식 구분을 남겨 두면 있지도 않은 선택이 된다.
            form.setSandbox(preset.hasKeyStages());
        }
        model.addAttribute("form", form);
        addPresets(model);
        return "connector/connect";
    }

    /**
     * 고를 수 있는 시스템 목록.
     *
     * <p>안내 문구를 따로 넘기지 않는다. 예전에는 시스템별 문구를 통째로 화면에 실어 보내고
     * 스크립트가 골라 쓰게 했는데, 그 스크립트가 보안 정책에 막혀 돌지 않았다. 문구는 각
     * 프리셋이 갖고 있고 화면이 <b>고른 것 하나만</b> 서버에서 읽는다.
     */
    private void addPresets(Model model) {
        model.addAttribute("presets", ApiAuthPreset.values());
    }

    /**
     * 연결하기. 확인과 저장을 <b>한 번에</b> 끝낸다.
     *
     * <p>예전에는 "테스트" 뒤에 "저장" 폼이 따로 떴고, 거기서 이름·코드·담을 곳·단위·인증키를
     * 다시 물었다. 그 다섯 가지 중 <b>사용자가 답할 수 있는 것은 없었다</b> — 코드가 무엇인지,
     * 재고 스냅샷이 무엇인지 알아야 답할 수 있는 질문이었기 때문이다.
     *
     * <p>지금은 확인에 성공하면 그 자리에서 저장한다. 답이 데이터에 있거나(담을 곳·단위) 우리가
     * 만들면 되는 것(이름·코드)은 묻지 않고, 방금 받은 인증키를 그대로 쓴다.
     *
     * <p><b>테스트 인증키는 예외다.</b> 그 키는 조회에 성공한 순간 죽으므로 저장해도 쓸 수 없다.
     * 저장하는 대신 "정식 키를 받아 오라"고 안내한다 — 저장해 두면 매일 실패하는 연동이 하나
     * 생기고, 사용자는 왜 안 되는지 알 수 없다.
     */
    @PostMapping("/connectors/connect/test")
    public String test(@ModelAttribute("form") ConnectionForm form, Model model,
                       RedirectAttributes redirectAttributes) {
        addPresets(model);
        // 결과가 언제 것인지 없으면, 화면에 남은 것이 방금 실행한 것인지 아까 것인지
        // 구분되지 않는다. 여러 번 시도할수록 헷갈린다.
        model.addAttribute("testedAt", LocalDateTime.now());
        try {
            ProbeReport report = probes.of(form.getPreset()).probe(form.toProbeRequest());
            model.addAttribute("report", report);
            if (report.isSuccess()) {
                fillSuggestions(form, report, model);
                // 테스트 키로 확인한 것도 저장한다.
                //
                // 검증에 성공한 회사코드·사용자 ID·접속 주소를 버릴 이유가 없다. 저장해 두면
                // 칸 맞추기까지 미리 해 놓고 정식 키만 나중에 갈아 끼우면 된다. 예전에는 정식
                // 키가 있어야만 저장돼서, 그것을 받아 오기까지 진도가 한 발도 나가지 않았다.
                return saveVerified(form, redirectAttributes);
            } else {
                model.addAttribute("error", "연결에 실패했습니다. 아래 단계를 확인하세요.");
            }
        } catch (BusinessException e) {
            // 입력이 모자란 경우다. 원격 호출 전에 막힌 것이므로 단계 결과가 없다.
            //
            // getMessage() 를 쓰지 않는다 — 그것은 "API_PROBE_INCOMPLETE(K016) args=[인증키]"
            // 같은 로그용 문자열이다. 사용자 화면에 그대로 나가면 무엇을 고쳐야 하는지 알 수 없다.
            model.addAttribute("error", errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
            if (e.is(ErrorCode.API_PROBE_INCOMPLETE) && e.messageArgs().length > 0) {
                // 어느 칸이 비었는지 화면이 짚어 준다. 문장만 있으면 칸을 눈으로 찾아야 한다.
                String field = String.valueOf(e.messageArgs()[0]);
                model.addAttribute("missingField", field);
                // 비밀값은 다시 채워지지 않으므로 가장 자주 비는 칸이다. 따로 표시해
                // "왜 또 비어 있지"를 설명한다.
                model.addAttribute("missingSecret",
                        form.getPreset() != null
                                && field.equals(form.getPreset().getSecretLabel()));
            }
        }
        // 비밀값은 화면으로 되돌리지 않는다. 다시 입력하게 하는 편이 안전하다.
        form.setSecret(null);
        return "connector/connect";
    }

    /**
     * 저장 설정을 <b>대신 정해 둔다.</b>
     *
     * <p>검증을 통과한 시점에 이미 답이 나와 있는 것들이 있다 — 어떤 칸을 받아왔는지 알면 어디에
     * 담을지가 정해지고, 어느 시스템에 붙었는지 알면 이름과 식별자도 만들 수 있다. 그런데도 빈
     * 칸으로 두고 물으면, 「재고 스냅샷이 뭔데」·「코드를 왜 내가 정해」에서 멈춘다.
     *
     * <p>사람이 정해야 하는 것은 <b>이름 하나</b>뿐이고, 그것도 기본값을 채워 둔 채 고치게 한다.
     * 빈 칸을 주면 대부분 「테스트」라고 적고 잊는다.
     */
    private void fillSuggestions(ConnectionForm form, ProbeReport report, Model model) {
        // 검증 직후에는 아직 사용자가 고른 적이 없다. 받아온 칸으로 판단한 값을 넣는다.
        String targetModel = ConnectionSuggestion.targetModel(report.fields());
        form.setTargetModelCode(targetModel);
        if (!StringUtils.hasText(form.getName())) {
            form.setName(ConnectionSuggestion.name(form.getPreset(), targetModel));
        }
        if (!StringUtils.hasText(form.getCode())) {
            form.setCode(ConnectionSuggestion.code(form.getPreset(), targetModel));
        }
        // 무엇으로 저장되는지 사람 말로. 모델 코드를 그대로 보여주면 읽히지 않는다.
        model.addAttribute("targetWord", ConnectionSuggestion.modelWord(targetModel));
    }

    /**
     * 확인된 설정을 그대로 저장한다.
     *
     * <p>"저장할까요?"를 한 번 더 묻지 않는다. 확인이 끝났다는 것은 쓸 수 있다는 뜻이고, 쓸 수
     * 있는 것을 저장할지 되묻는 것은 사용자에게 <b>의미 없는 선택지</b>다. 되묻는 대신 바로
     * 다음 할 일(매핑)로 보낸다.
     */
    private String saveVerified(ConnectionForm form, RedirectAttributes redirectAttributes) {
        try {
            var connector = connectionService.saveConnection(form);
            // flash 이름은 layout 이 읽는 것과 같아야 한다. message/error 로 담으면 화면에
            // 아무것도 뜨지 않는다 — 다음 단계로 넘기는 가장 중요한 안내가 통째로 사라진다.
            redirectAttributes.addFlashAttribute("flashSuccess", nextStepMessage(form));
            return "redirect:/connectors/" + connector.getId() + "/mapping";
        } catch (BusinessException e) {
            // 저장 경로도 같다 — 로그용 문자열을 화면에 내보내지 않는다.
            redirectAttributes.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
            return "redirect:/connectors/connect";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/connectors/connect";
        }
    }

    /**
     * 저장한 뒤 무엇이 남았는지.
     *
     * <p>테스트 키로 저장한 경우와 정식 키로 저장한 경우는 <b>남은 일이 다르다.</b> 테스트 키는
     * 유효기간이 짧아 매일 도는 수집에 쓸 수 없으므로, 칸을 맞춘 뒤 키를 바꿔야 한다는 것을
     * 여기서 알려 주지 않으면 며칠 뒤 조용히 멈춘 뒤에야 알게 된다.
     */
    private String nextStepMessage(ConnectionForm form) {
        if (form.isSandbox() && form.getPreset().hasKeyStages()) {
            return "연결됐습니다. 칸을 맞춘 뒤 정식 인증키로 바꾸면 매일 자동으로 가져옵니다.";
        }
        return "연결됐습니다. 이제 어떤 항목을 어디에 담을지만 정하면 끝입니다.";
    }
}
