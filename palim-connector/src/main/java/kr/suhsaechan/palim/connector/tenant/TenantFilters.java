package kr.suhsaechan.palim.connector.tenant;

/**
 * 필터 이름 상수.
 *
 * <p>문자열을 엔티티마다 적으면 오타 하나로 그 엔티티만 조용히 필터를 벗어난다. 컴파일러가
 * 잡도록 상수로 둔다.
 */
public final class TenantFilters {

    public static final String TENANT_FILTER = "tenantFilter";
    public static final String TENANT_PARAM = "tenantId";

    /** 엔티티의 {@code @Filter} 에 그대로 쓰는 조건. */
    public static final String TENANT_CONDITION = "tenant_id = :tenantId";

    private TenantFilters() {
    }
}
