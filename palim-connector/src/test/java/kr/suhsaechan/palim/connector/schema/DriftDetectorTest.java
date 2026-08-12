package kr.suhsaechan.palim.connector.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import kr.suhsaechan.palim.connector.source.SourceSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 원천 양식 변화 감지.
 *
 * <p>이 시스템의 최악 실패는 <b>양식이 바뀌었는데 조용히 잘못된 데이터가 들어가는 것</b>이다.
 * 다만 과민하면 사람이 감지를 꺼버리고, 꺼진 안전장치는 없는 것과 같다. 그래서 <b>매핑에
 * 실제로 쓰는 필드</b>가 사라졌을 때만 막는다.
 */
class DriftDetectorTest {

    private final DriftDetector detector = new DriftDetector();

    @Test
    @DisplayName("매핑에 쓰는 필드가 사라지면 막는다")
    void 사용중인_필드가_사라지면_차단() {
        DriftVerdict verdict = detector.detect(
                new SchemaSnapshot(List.of("코드", "이름", "수량")),
                schema("코드", "이름"),
                Set.of("코드", "수량"));

        assertThat(verdict.blocking()).isTrue();
        assertThat(verdict.removed()).containsExactly("수량");
        assertThat(verdict.summary()).contains("수량");
    }

    @Test
    @DisplayName("매핑에 쓰지 않는 필드가 사라지면 통과시킨다")
    void 미사용_필드가_사라지면_통과() {
        DriftVerdict verdict = detector.detect(
                new SchemaSnapshot(List.of("코드", "이름", "메모")),
                schema("코드", "이름"),
                Set.of("코드", "이름"));

        assertThat(verdict.blocking()).as("쓰지 않는 필드는 없어져도 대사에 영향이 없다").isFalse();
        assertThat(verdict.removed()).containsExactly("메모");
    }

    @Test
    @DisplayName("새 필드가 추가되면 통과시킨다")
    void 필드_추가는_통과() {
        DriftVerdict verdict = detector.detect(
                new SchemaSnapshot(List.of("코드", "이름")),
                schema("코드", "이름", "신규"),
                Set.of("코드", "이름"));

        assertThat(verdict.blocking())
                .as("추가만으로 업무를 멈추면 사람이 감지를 꺼버린다").isFalse();
        assertThat(verdict.added()).containsExactly("신규");
        assertThat(verdict.summary()).contains("신규");
    }

    @Test
    @DisplayName("변화가 없으면 통과이고 요약도 비어 있다")
    void 변화가_없으면_통과() {
        DriftVerdict verdict = detector.detect(
                new SchemaSnapshot(List.of("코드", "이름")),
                schema("코드", "이름"),
                Set.of("코드"));

        assertThat(verdict.blocking()).isFalse();
        assertThat(verdict.removed()).isEmpty();
        assertThat(verdict.added()).isEmpty();
        assertThat(verdict.summary()).isEmpty();
    }

    @Test
    @DisplayName("사용 중인 필드가 사라지면 추가가 함께 있어도 막는다")
    void 추가가_있어도_삭제가_우선한다() {
        DriftVerdict verdict = detector.detect(
                new SchemaSnapshot(List.of("코드", "수량")),
                schema("코드", "신규"),
                Set.of("코드", "수량"));

        assertThat(verdict.blocking()).isTrue();
        assertThat(verdict.added()).containsExactly("신규");
        assertThat(verdict.removed()).containsExactly("수량");
    }

    @Test
    @DisplayName("확정 스냅샷이 비어 있으면 첫 실행이므로 막지 않는다")
    void 스냅샷이_비면_통과() {
        DriftVerdict verdict = detector.detect(
                new SchemaSnapshot(List.of()),
                schema("코드", "수량"),
                Set.of("코드"));

        assertThat(verdict.blocking()).isFalse();
    }

    private SourceSchema schema(String... fields) {
        return new SourceSchema(List.of(fields), List.of(), 0);
    }
}
