package kr.suhsaechan.palim.connector.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 단위 환산.
 *
 * <p>실패 조건을 <b>좁게</b> 잡는 것이 핵심이다. "규칙이 없으면 무조건 실패"로 만들면 단위
 * 개념이 없는 원천이 통째로 막히는데, 실측한 두 원천 모두 단위 컬럼이 없다. 반대로 아무 때나
 * 1:1 로 넘기면 BOX 12개가 EA 12개로 둔갑하고 대사 결과가 이상해질 때까지 아무도 모른다.
 */
class UnitConverterTest {

    private static final UUID TENANT = UUID.randomUUID();

    private UnitConversionRepository repository;
    private UnitConverter converter;

    @BeforeEach
    void setUp() {
        repository = mock(UnitConversionRepository.class);
        converter = new UnitConverter(repository);
    }

    @Test
    @DisplayName("단위가 비어 있으면 환산 없이 기준 단위로 통과한다")
    void 단위가_없으면_통과() {
        ConvertedQuantity result = converter.convert(TENANT, "A-001",
                new BigDecimal("120"), null, "EA");

        assertThat(result.baseQuantity()).isEqualByComparingTo("120");
        assertThat(result.baseUnit()).isEqualTo("EA");
        verify(repository, never()).findFactors(any(), any(), any(), any());
    }

    @Test
    @DisplayName("빈 문자열 단위도 없는 것으로 본다")
    void 공백_단위도_통과() {
        ConvertedQuantity result = converter.convert(TENANT, "A-001",
                new BigDecimal("5"), "   ", "EA");

        assertThat(result.baseQuantity()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("단위가 기준 단위와 같으면 규칙 없이 통과한다")
    void 같은_단위는_규칙이_필요없다() {
        ConvertedQuantity result = converter.convert(TENANT, "A-001",
                new BigDecimal("5"), "EA", "EA");

        assertThat(result.baseQuantity()).isEqualByComparingTo("5");
        verify(repository, never()).findFactors(any(), any(), any(), any());
    }

    @Test
    @DisplayName("규칙이 있으면 환산하고 원본도 함께 남긴다")
    void 규칙으로_환산한다() {
        when(repository.findFactors(TENANT, "A-001", "BOX", "EA"))
                .thenReturn(List.of(new BigDecimal("12")));

        ConvertedQuantity result = converter.convert(TENANT, "A-001",
                new BigDecimal("12"), "BOX", "EA");

        assertThat(result.baseQuantity()).isEqualByComparingTo("144");
        assertThat(result.quantity()).as("원본이 없으면 원천이 뭐라고 줬는지 확인할 수 없다")
                .isEqualByComparingTo("12");
        assertThat(result.unit()).isEqualTo("BOX");
        assertThat(result.baseUnit()).isEqualTo("EA");
    }

    @Test
    @DisplayName("단위가 명시됐는데 규칙이 없으면 실패시킨다")
    void 규칙이_없으면_실패() {
        when(repository.findFactors(TENANT, "A-001", "BOX", "EA")).thenReturn(List.of());

        assertThatThrownBy(() -> converter.convert(TENANT, "A-001",
                new BigDecimal("12"), "BOX", "EA"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNIT_CONVERSION_NOT_FOUND);
    }

    @Test
    @DisplayName("품목별 규칙이 전역 규칙보다 앞선다")
    void 품목별_규칙이_우선한다() {
        // 리포지토리가 품목별을 앞에 두고 정렬해 돌려준다. 첫 값만 쓴다.
        when(repository.findFactors(TENANT, "A-001", "BOX", "EA"))
                .thenReturn(List.of(new BigDecimal("6"), new BigDecimal("12")));

        ConvertedQuantity result = converter.convert(TENANT, "A-001",
                new BigDecimal("2"), "BOX", "EA");

        assertThat(result.baseQuantity()).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("소수 배율도 정확히 환산한다")
    void 소수_배율() {
        when(repository.findFactors(TENANT, "A-001", "G", "KG"))
                .thenReturn(List.of(new BigDecimal("0.001")));

        ConvertedQuantity result = converter.convert(TENANT, "A-001",
                new BigDecimal("2500"), "G", "KG");

        assertThat(result.baseQuantity()).isEqualByComparingTo("2.5");
    }
}
