/**
 * 테넌트 격리.
 *
 * <p>모든 연동 엔티티에 {@code tenant_id} 가 있고, 조회는 이 필터를 거친다. 필터를 켜는 곳이
 * 한 군데라 개별 쿼리가 조건을 빠뜨릴 수 없다 — 각 쿼리에 맡기면 반드시 빠뜨리는 곳이 생기고,
 * 그것이 곧 데이터 유출이다.
 *
 * <p>{@code autoEnabled = true} 이므로 세션이 열릴 때 자동으로 켜진다. 서비스가 활성화를
 * 잊는 경로가 아예 없다.
 */
@FilterDef(
        name = TenantFilters.TENANT_FILTER,
        autoEnabled = true,
        parameters = @ParamDef(
                name = TenantFilters.TENANT_PARAM,
                type = java.util.UUID.class,
                resolver = TenantIdResolver.class))
package kr.suhsaechan.palim.common.tenant;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
