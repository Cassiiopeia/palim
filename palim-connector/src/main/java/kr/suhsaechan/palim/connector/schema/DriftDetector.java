package kr.suhsaechan.palim.connector.schema;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kr.suhsaechan.palim.connector.source.SourceSchema;
import org.springframework.stereotype.Component;

/**
 * 원천 양식 변화 감지.
 *
 * <p>변화 종류에 따라 대응을 나눈다. "다르면 무조건 중단"으로 만들면 컬럼이 하나 추가되기만
 * 해도 업무가 멈추고, <b>그러면 사람이 감지를 꺼버린다.</b> 꺼진 안전장치는 없는 것과 같으므로
 * 실제로 위험한 변화만 막는다.
 *
 * <table border="1">
 *   <caption>대응</caption>
 *   <tr><td>매핑에 쓰던 필드가 사라짐</td><td><b>중단</b> — 그 필드로 만들던 값이 전부 빈다</td></tr>
 *   <tr><td>매핑에 쓰지 않는 필드가 사라짐</td><td>경고 — 대사 결과에 영향이 없다</td></tr>
 *   <tr><td>새 필드가 추가됨</td><td>경고 — {@code attributes} 로 보존된다</td></tr>
 * </table>
 */
@Component
public class DriftDetector {

    /**
     * @param confirmed    매핑 확정 당시의 필드 목록
     * @param current      이번 실행에서 읽은 원천의 필드 목록
     * @param mappedFields 매핑에 실제로 쓰이는 원천 필드 이름
     */
    public DriftVerdict detect(SchemaSnapshot confirmed, SourceSchema current,
                               Set<String> mappedFields) {
        // 확정 이력이 없으면 첫 실행이다. 대조 기준이 없는데 막으면 아무것도 시작할 수 없다.
        if (confirmed.isEmpty()) {
            return DriftVerdict.clean();
        }

        Set<String> currentFields = new LinkedHashSet<>(current.fields());
        Set<String> confirmedFields = new LinkedHashSet<>(confirmed.fields());

        List<String> removed = confirmed.fields().stream()
                .filter(field -> !currentFields.contains(field))
                .toList();

        List<String> added = current.fields().stream()
                .filter(field -> !confirmedFields.contains(field))
                .toList();

        List<String> blockingFields = removed.stream()
                .filter(mappedFields::contains)
                .toList();

        if (!blockingFields.isEmpty()) {
            return new DriftVerdict(true, removed, added,
                    "매핑에 사용 중인 항목이 사라졌습니다: " + String.join(", ", blockingFields));
        }
        return new DriftVerdict(false, removed, added, describe(removed, added));
    }

    private String describe(List<String> removed, List<String> added) {
        if (removed.isEmpty() && added.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (!added.isEmpty()) {
            builder.append("새 항목: ").append(String.join(", ", added));
        }
        if (!removed.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append(" / ");
            }
            builder.append("사라진 항목(미사용): ").append(String.join(", ", removed));
        }
        return builder.toString();
    }
}
