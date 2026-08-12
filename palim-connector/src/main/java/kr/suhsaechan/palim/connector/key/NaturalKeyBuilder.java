package kr.suhsaechan.palim.connector.key;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.support.ColumnText;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 자연키 생성.
 *
 * <p>"무엇이 같으면 같은 행인가"를 문자열 하나로 만든다. UPSERT 의 기준이므로 <b>서로 다른
 * 행이 같은 키가 되는 순간 데이터가 조용히 사라진다.</b> 그래서 세 가지를 지킨다.
 *
 * <ol>
 *   <li><b>구분자는 유니트 구분자(U+001F)</b> — 사람이 입력하는 값에 등장할 일이 없다.
 *       {@code |} 같은 문자를 쓰면 값에 그 문자가 포함될 때 {@code "A|B"+"C"} 와
 *       {@code "A"+"B|C"} 가 같은 키가 된다</li>
 *   <li><b>빈 값도 자리를 지킨다</b> — 건너뛰면 뒤 값이 앞으로 밀려 다른 조합과 겹친다</li>
 *   <li><b>길이 초과는 절단이 아니라 해시 축약</b> — 앞부분이 같은 긴 키들이 하나로 합쳐지면
 *       남의 행을 덮어쓴다</li>
 * </ol>
 */
@Component
public class NaturalKeyBuilder {

    /** 유니트 구분자(U+001F). 텍스트 데이터에 나타나지 않는다. */
    private static final String SEPARATOR = "";

    /** {@code natural_key} 컬럼 길이. */
    private static final int MAX_LENGTH = 500;

    public String build(Map<String, Object> values, List<String> keyFields) {
        if (keyFields == null || keyFields.isEmpty()) {
            // 중복 판정 기준이 없으면 재실행이 매번 새 행을 만든다.
            throw new BusinessException(ErrorCode.NATURAL_KEY_INCOMPLETE, "(정의 없음)");
        }

        List<String> parts = keyFields.stream()
                .map(field -> Objects.toString(values.get(field), ""))
                .toList();

        if (parts.stream().noneMatch(StringUtils::hasText)) {
            throw new BusinessException(ErrorCode.NATURAL_KEY_INCOMPLETE,
                    String.join(", ", keyFields));
        }
        return ColumnText.shortenKey(String.join(SEPARATOR, parts), MAX_LENGTH);
    }
}
