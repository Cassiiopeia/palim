package kr.suhsaechan.palim.notification.payload;

import java.time.LocalDate;
import java.util.List;

/**
 * 재고 대조 하루 요약.
 *
 * <p><b>이상이 없어도 보낸다.</b> 그래야 무음이 사라진다 — 지금까지 「아무것도 안 옴」 은
 * 「오늘 깨끗함」·「전부 첫 관찰이라 보류」·「알릴 기준을 안 정함」·「며칠째 막힘」·「보낼 곳이
 * 연결 안 돼 쌓이는 중」 을 동시에 뜻해서, 열지 않고는 어느 쪽인지 알 수 없었다.
 *
 * <p><b>미매칭과 기준 미설정을 반드시 담는다.</b> 이 둘은 지금까지 <b>영영 알림으로 나가지
 * 않았다</b> — 품목이 안 붙은 채 방치되면 견주는 범위가 조용히 줄어드는데, 그 사실이 알림
 * 경로에 한 번도 등장하지 않았다.
 *
 * @param targetDate    무엇을 견줬는가(그날 자료). 표시용 지역 날짜다
 * @param definitions   활성 대조 수
 * @param succeeded     그중 정상으로 끝난 수
 * @param withDiff      차이가 나온 대조 수
 * @param diffCount     알릴 만한 차이 건수 합
 * @param unmatched     짝을 못 찾은 품목 수 — 견주지 못하고 빠진 것들이다
 * @param withoutThreshold 알릴 기준을 정하지 않은 대조 수 — 차이가 나도 조용하다
 * @param blocked       며칠째 막힌 대조들
 * @param lines         대조별 한 줄 요약
 */
public record ReconcileDigestPayload(
        LocalDate targetDate,
        int definitions,
        int succeeded,
        int withDiff,
        int diffCount,
        int unmatched,
        int withoutThreshold,
        List<Blocked> blocked,
        List<String> lines
) {

    /** 막힌 대조 하나. */
    public record Blocked(String definition, int days) {
    }

    /**
     * 제목.
     *
     * <p><b>이 요약의 존재 이유가 이 한 줄이다</b> — 열지 않아도 오늘 볼 일이 있는지 판단해야
     * 한다. 그래서 심각한 쪽이 제목을 가져간다: 막힘 &gt; 차이 &gt; 설정 필요 &gt; 이상 없음.
     *
     * <p>메일 제목과 메신저 첫 줄이 <b>같은 문자열</b>이어야 한다. 두 곳이 갈리면 어느 쪽을
     * 보느냐에 따라 판단이 달라진다.
     */
    public String subject() {
        if (!blocked.isEmpty()) {
            int days = blocked.stream().mapToInt(Blocked::days).max().orElse(1);
            return "[대조] 막힘 %d건 · %d일째".formatted(blocked.size(), days);
        }
        if (diffCount > 0) {
            return "[대조] 차이 %d건 · 대조 %d개".formatted(diffCount, withDiff);
        }
        if (withoutThreshold > 0) {
            return "[대조] 설정 필요 — 알릴 기준이 없습니다";
        }
        if (succeeded < definitions) {
            // 며칠째인지가 문턱 아래라 제목의 「막힘」 에는 안 걸렸지만, 오늘 안 돈 것은 사실이다.
            // 「이상 없음」 이라고 하면 그 사실이 통째로 사라진다.
            return "[대조] 대조 %d개 중 %d개만 돎".formatted(definitions, succeeded);
        }
        if (unmatched > 0) {
            return "[대조] 이상 없음 · 짝 없는 품목 %d개".formatted(unmatched);
        }
        return "[대조] 이상 없음 · 대조 %d개".formatted(definitions);
    }

    /** 볼 일이 있는가. 화면과 로그가 같은 기준으로 말하게 한다. */
    public boolean needsAttention() {
        return !blocked.isEmpty() || diffCount > 0 || withoutThreshold > 0
                || succeeded < definitions;
    }
}
