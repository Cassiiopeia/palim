package kr.suhsaechan.palim.connector.source.http;

import java.util.List;
import java.util.Map;

/**
 * 연결 검증 결과 전체.
 *
 * <p>성공했을 때 <b>응답 필드 목록과 샘플 행</b>을 함께 돌려주는 것이 핵심이다. 매핑을 짜려면
 * 실제 필드명을 알아야 하는데, 문서에 적힌 이름과 응답에 실제로 오는 이름이 다른 일이 흔하다.
 * 검증과 필드 확인을 한 번에 끝내는 이유는 <b>사람을 한 번만 기다리게 하려는 것</b>이다.
 * 연결됐다는 말만 듣고 필드를 보려고 또 실행하게 만들면, 그 사이 무엇이 달라졌는지 알 수 없다.
 *
 * @param steps   단계별 결과 (실행 순서대로)
 * @param fields  응답에서 확인된 필드 이름
 * @param samples 샘플 행 몇 개. 값의 생김새를 봐야 매핑을 정할 수 있다
 * @param totalCount 응답이 알려준 전체 건수. 모르면 -1
 */
public record ProbeReport(List<ProbeStep> steps, List<String> fields,
                          List<Map<String, String>> samples, int totalCount) {

    /** 모든 단계가 성공했는가. 한 단계라도 실패하면 연결이 성립하지 않은 것이다. */
    public boolean isSuccess() {
        return !steps.isEmpty() && steps.stream().allMatch(ProbeStep::success);
    }

    /** 실패한 첫 단계. 사람에게 보여줄 원인은 이것 하나면 충분하다. */
    public ProbeStep firstFailure() {
        return steps.stream().filter(step -> !step.success()).findFirst().orElse(null);
    }

    public static ProbeReport of(List<ProbeStep> steps) {
        return new ProbeReport(steps, List.of(), List.of(), -1);
    }
}
