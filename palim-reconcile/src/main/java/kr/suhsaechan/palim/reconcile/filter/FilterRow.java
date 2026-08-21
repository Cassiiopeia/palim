package kr.suhsaechan.palim.reconcile.filter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.tenant.TenantFilters;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 저장된 조건 한 줄.
 *
 * <p><b>값은 언제나 배열이다.</b> {@code EQ} 는 원소 하나, {@code BETWEEN} 은 둘,
 * {@code IS_EMPTY} 는 없음. 모양이 하나면 화면·검증·SQL 조립이 전부 한 갈래로 끝난다.
 * 연산자마다 저장 모양이 다르면 그 조합만큼 분기가 생기고, 안 쓰는 분기부터 썩는다.
 *
 * <p>식({@code EXPRESSION})도 같은 표에 담는다. 순서와 좌우가 조건 줄과 같은 개념이라
 * 표를 가르면 「어느 쪽이 먼저인가」 를 두 곳에서 맞춰야 한다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "reconcile_filter")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FilterRow extends BaseTimeEntity {

    /** 조건 줄. */
    public static final String TYPE_FIELD = "FIELD";
    /** 식. {@code values_json} 에 글 하나가 든다. */
    public static final String TYPE_EXPRESSION = "EXPRESSION";

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID definitionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FilterSide side;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, length = 20)
    private String rowType;

    @Column(nullable = false, length = 200)
    private String fieldKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FilterOperator operator;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "values_json", nullable = false, columnDefinition = "jsonb")
    private List<String> values;

    private FilterRow(UUID tenantId, UUID definitionId, FilterSide side, int ordinal,
                      String rowType, String fieldKey, FilterOperator operator,
                      List<String> values) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.definitionId = definitionId;
        this.side = side;
        this.ordinal = ordinal;
        this.rowType = rowType;
        this.fieldKey = fieldKey;
        this.operator = operator;
        this.values = values == null ? List.of() : List.copyOf(values);
    }

    /** 조건 줄 하나. */
    public static FilterRow field(UUID tenantId, UUID definitionId, FilterSide side,
                                  int ordinal, String fieldKey, FilterOperator operator,
                                  List<String> values) {
        return new FilterRow(tenantId, definitionId, side, ordinal,
                TYPE_FIELD, fieldKey, operator, values);
    }

    /**
     * 식 한 줄. 한 side 에 하나만 둔다.
     *
     * <p>{@code operator} 는 쓰이지 않지만 {@code NOT NULL} 이라 아무 값이나 채운다 —
     * enum 컬럼에 빈 문자열을 넣으면 읽을 때 터진다.
     */
    public static FilterRow expression(UUID tenantId, UUID definitionId, FilterSide side,
                                       int ordinal, String text) {
        return new FilterRow(tenantId, definitionId, side, ordinal,
                TYPE_EXPRESSION, "", FilterOperator.EQ, List.of(text));
    }

    public boolean isExpression() {
        return TYPE_EXPRESSION.equals(rowType);
    }

    /** 식의 글. 조건 줄이면 빈 문자열. */
    public String getExpression() {
        return isExpression() && !values.isEmpty() ? values.get(0) : "";
    }

    /** 화면이 한 칸에 담아 보내는 모양. 값 여럿을 구분자로 잇는다. */
    public String joinedValues() {
        return String.join(FilterValues.DELIMITER, values);
    }
}
