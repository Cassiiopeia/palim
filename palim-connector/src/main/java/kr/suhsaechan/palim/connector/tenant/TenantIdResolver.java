package kr.suhsaechan.palim.connector.tenant;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 필터 파라미터 해석기.
 *
 * <p>Hibernate 가 필터를 켤 때 이 공급자에서 테넌트를 읽는다. 각 서비스가 필터를 직접
 * 활성화하고 파라미터를 넘기게 두면 <b>한 곳만 빠뜨려도 다른 테넌트의 데이터가 보인다.</b>
 */
public class TenantIdResolver implements Supplier<UUID> {

    @Override
    public UUID get() {
        return TenantContext.current();
    }
}
