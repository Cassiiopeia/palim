package kr.suhsaechan.palim.connector.unit;

import java.math.BigDecimal;

/**
 * 환산 결과.
 *
 * <p>원본과 환산값을 <b>둘 다</b> 남긴다. 집계·대사는 {@code baseQuantity} 만 쓰지만, 원본이
 * 없으면 "원천이 뭐라고 줬는지"를 나중에 확인할 방법이 사라진다 — 환산 규칙을 잘못 넣었을 때
 * 그것을 알아챌 근거가 원본뿐이다.
 *
 * @param quantity     원천이 준 수량 그대로
 * @param unit         원천이 준 단위. 없었으면 {@code null}
 * @param baseQuantity 기준 단위로 환산한 값. 집계는 이것만 쓴다
 * @param baseUnit     커넥터의 기준 단위
 */
public record ConvertedQuantity(BigDecimal quantity, String unit,
                                BigDecimal baseQuantity, String baseUnit) {
}
