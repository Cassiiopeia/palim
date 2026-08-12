package kr.suhsaechan.palim.connector.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 길이 제한 컬럼 처리.
 *
 * <p>PostgreSQL 은 {@code varchar(n)} 초과를 조용히 자르지 않고 22001 로 트랜잭션을 중단시킨다.
 * 실패 행을 기록하려다 실행이 죽는 것을 막는 것이 이 클래스의 목적이다.
 */
class ColumnTextTest {

    @Test
    @DisplayName("길이 안이면 그대로 둔다")
    void 짧으면_그대로() {
        assertThat(ColumnText.truncate("짧은 메시지", 100)).isEqualTo("짧은 메시지");
        assertThat(ColumnText.shortenKey("A-001", 500)).isEqualTo("A-001");
    }

    @Test
    @DisplayName("null 은 그대로 통과시킨다")
    void null_은_통과() {
        assertThat(ColumnText.truncate(null, 100)).isNull();
        assertThat(ColumnText.shortenKey(null, 100)).isNull();
    }

    @Test
    @DisplayName("표시용 문자열은 길이에 맞춰 자른다")
    void 메시지를_자른다() {
        String message = "가".repeat(2000);

        String result = ColumnText.truncate(message, 1000);

        assertThat(result).hasSize(1000);
        assertThat(result).endsWith("…");
    }

    @Test
    @DisplayName("경계값 — 정확히 최대 길이면 자르지 않는다")
    void 경계값은_자르지_않는다() {
        String exact = "가".repeat(100);

        assertThat(ColumnText.truncate(exact, 100)).isEqualTo(exact);
    }

    @Test
    @DisplayName("식별자는 잘라도 길이를 넘지 않는다")
    void 자연키가_길이를_지킨다() {
        String key = "A".repeat(3000);

        String result = ColumnText.shortenKey(key, 500);

        assertThat(result).hasSize(500);
    }

    @Test
    @DisplayName("앞부분이 같은 서로 다른 식별자는 구분된다 — 이 클래스의 존재 이유")
    void 앞부분이_같아도_충돌하지_않는다() {
        String prefix = "A".repeat(600);
        String first = prefix + "재고1";
        String second = prefix + "재고2";

        String shortenedFirst = ColumnText.shortenKey(first, 500);
        String shortenedSecond = ColumnText.shortenKey(second, 500);

        assertThat(shortenedFirst)
                .as("단순 절단이면 두 키가 같아지고 UPSERT 가 남의 행을 덮어쓴다")
                .isNotEqualTo(shortenedSecond);
    }

    @Test
    @DisplayName("같은 식별자는 항상 같은 결과를 낸다 — 재실행이 중복을 만들면 안 된다")
    void 같은_키는_같은_결과() {
        String key = "B".repeat(800);

        assertThat(ColumnText.shortenKey(key, 500))
                .isEqualTo(ColumnText.shortenKey(key, 500));
    }

    @Test
    @DisplayName("축약해도 앞부분이 남아 사람이 어떤 행인지 알아본다")
    void 앞부분을_보존한다() {
        String key = "ERPA-001" + "X".repeat(600);

        String result = ColumnText.shortenKey(key, 500);

        assertThat(result).startsWith("ERP");
    }
}
