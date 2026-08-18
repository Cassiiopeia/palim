package kr.suhsaechan.palim.reconcile.define;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import kr.suhsaechan.palim.common.BaseAtGranularity;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.tenant.TenantFilters;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

/**
 * 무엇을 비교할지.
 *
 * <p>비교 대상을 <b>정의 데이터로 받는다.</b> 코드에 박으면 「금액도 맞춰 보고 싶다」는 요구가
 * 왔을 때 대조 엔진을 고쳐야 한다. {@code compareField} 만 바꾸면 같은 엔진이 금액 대조가 된다.
 *
 * <p>{@code tolerance} 는 «이 정도 차이는 차이로 보지 않는다» 는 선이다. 소수점 반올림이나
 * 낱개 한두 개까지 전부 띄우면 목록이 잡음으로 차서 진짜 문제가 묻힌다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "reconcile_definition")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconcileDefinition extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    /** 좌측 원천 이름. 스냅샷의 {@code source} 와 같은 값. */
    @Column(nullable = false, length = 50)
    private String leftSource;

    @Column(nullable = false, length = 50)
    private String rightSource;

    @Column(nullable = false, length = 100)
    private String targetTable;

    /** 비교할 수치 칸. 이 값을 바꾸면 금액 대조가 된다. */
    @Column(nullable = false, length = 100)
    private String compareField;

    /** 이 이하 차이는 기록하지 않는다. */
    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal tolerance;

    /** 비어 있으면 알리지 않는다. 매일 도는 일이 매일 알림을 보내면 아무도 안 본다. */
    @Column(precision = 19, scale = 3)
    private BigDecimal alertThreshold;

    /**
     * 견줄 <b>눈금</b>. 두 원천의 기준 시각을 이 굵기로 내려 같은 칸에 들어오는지 본다.
     *
     * <p>원천마다 실제 해상도가 다르다 — 전산은 기준일을 날짜로만 받고, 물류는 「지금 재고」를
     * 준다. 「정확히 같은 시각」을 요구하면 대조는 사실상 절대 돌지 않는다.
     *
     * <p><b>수집 눈금보다 굵어야 한다.</b> 하루 한 번 담는 원천을 시간 눈금으로 견주면 두 원천이
     * 같은 칸에 들어오는 일이 없다. 기본값이 하루인 이유다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BaseAtGranularity baseAtGranularity;

    @Column(nullable = false)
    private boolean isActive;

    /** 합계를 뜯어볼 기준. 「NAME」·「NONE」·「FIELD:칸이름」. 기본은 품명이 닮은 것끼리. */
    @Column(nullable = false, length = 100)
    private String breakdownAxis = "NAME";

    private ReconcileDefinition(UUID tenantId, String code, String name, String leftSource,
                                String rightSource, String compareField, BigDecimal tolerance,
                                BigDecimal alertThreshold) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.leftSource = leftSource;
        this.rightSource = rightSource;
        this.targetTable = "std_stock_snapshot";
        this.compareField = compareField == null ? "base_quantity" : compareField;
        this.tolerance = tolerance == null ? BigDecimal.ZERO : tolerance;
        this.alertThreshold = alertThreshold;
        this.baseAtGranularity = BaseAtGranularity.DAY;
        this.isActive = true;
    }

    public static ReconcileDefinition of(UUID tenantId, String code, String name,
                                         String leftSource, String rightSource,
                                         String compareField, BigDecimal tolerance,
                                         BigDecimal alertThreshold) {
        return new ReconcileDefinition(tenantId, code, name, leftSource, rightSource,
                compareField, tolerance, alertThreshold);
    }

    public void changeTolerance(BigDecimal tolerance) {
        this.tolerance = tolerance == null ? BigDecimal.ZERO : tolerance;
    }

    public void changeAlertThreshold(BigDecimal alertThreshold) {
        this.alertThreshold = alertThreshold;
    }

    /** 견줄 눈금을 바꾼다. 비우면 하루로 되돌린다. */
    public void changeBaseAtGranularity(BaseAtGranularity granularity) {
        this.baseAtGranularity = granularity == null ? BaseAtGranularity.DAY : granularity;
    }

    /** 예전에 만든 정의는 이 값이 비어 있을 수 있다 — 그때의 동작인 하루로 본다. */
    public BaseAtGranularity granularityOrDay() {
        return baseAtGranularity == null ? BaseAtGranularity.DAY : baseAtGranularity;
    }

    /**
     * 합계를 무엇을 기준으로 뜯어볼지.
     *
     * <p>자료 구조가 회사마다 다르므로 기준도 다르다 — 로트가 품명에 섞여 오는 곳, 별도 칸으로
     * 오는 곳, 창고별로 봐야 하는 곳. 코드에 박아 두면 그런 곳에서는 고칠 방법이 없다
     * (07-DECISIONS 040).
     */
    public void changeBreakdownAxis(String breakdownAxis) {
        this.breakdownAxis = breakdownAxis == null || breakdownAxis.isBlank()
                ? "NAME" : breakdownAxis;
    }

    public void deactivate() {
        this.isActive = false;
    }
}
