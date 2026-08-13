package kr.suhsaechan.palim.common.tenant;

import java.util.UUID;

/**
 * 현재 요청의 테넌트.
 *
 * <p>지금은 기본 테넌트 하나로 운영한다. 값을 <b>한 곳에서만</b> 읽게 해두면, SaaS 로 갈 때
 * 로그인 세션에서 테넌트를 꺼내는 것으로 바뀌고 나머지 코드는 그대로다.
 *
 * <p>{@link ThreadLocal} 을 쓰는 이유는 요청 스레드마다 값이 달라야 하기 때문이다. 정적 필드
 * 하나로 두면 동시 요청에서 서로의 테넌트를 덮어쓴다 — 그것이 곧 데이터 유출이다.
 */
public final class TenantContext {

    /** 멀티테넌시 전 단계의 기본 테넌트. 마이그레이션이 이 값으로 한 행을 넣는다. */
    public static final UUID DEFAULT_TENANT_ID =
            UUID.fromString("00000000-0000-7000-8000-000000000001");

    private static final ThreadLocal<UUID> CURRENT =
            ThreadLocal.withInitial(() -> DEFAULT_TENANT_ID);

    private TenantContext() {
    }

    public static UUID current() {
        return CURRENT.get();
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId == null ? DEFAULT_TENANT_ID : tenantId);
    }

    /**
     * 원래 값으로 되돌린다.
     *
     * <p>스레드 풀에서 스레드가 재사용되므로 <b>반드시 정리해야 한다.</b> 남겨두면 다음 요청이
     * 앞 요청의 테넌트로 조회한다.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
