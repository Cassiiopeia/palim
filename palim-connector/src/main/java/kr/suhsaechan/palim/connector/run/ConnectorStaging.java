package kr.suhsaechan.palim.connector.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.connector.support.ColumnText;
import kr.suhsaechan.palim.connector.tenant.TenantFilters;
import org.hibernate.annotations.Filter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * TEST 실행의 변환 결과 1행.
 *
 * <p>목표 모델의 컬럼이 아니라 JSONB 로 담는다. TEST 는 모델을 가리지 않고 돌아야 하는데
 * 모델별 테이블에 쓰면 그 순간 운영 데이터에 닿는다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "connector_staging")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorStaging extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID runId;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    /** LIVE 였다면 무엇을 기준으로 UPSERT 됐을지. 미리보기에서 중복을 알아볼 수 있다. */
    @Column(nullable = false, length = 500)
    private String naturalKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    private ConnectorStaging(UUID tenantId, UUID runId, int rowNumber, String naturalKey,
                             Map<String, Object> payload) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.runId = runId;
        this.rowNumber = rowNumber;
        this.naturalKey = naturalKey;
        this.payload = payload;
    }

    /** {@code natural_key} 컬럼 길이. */
    private static final int NATURAL_KEY_MAX_LENGTH = 500;

    /**
     * 스테이징 적재.
     *
     * <p>자연키는 <b>자르지 않고 해시로 축약</b>한다. 단순 절단하면 앞부분이 같은 서로 다른
     * 두 행이 같은 키가 되어, 나중에 LIVE 로 올릴 때 남의 행을 덮어쓴다.
     */
    public static ConnectorStaging of(UUID tenantId, UUID runId, int rowNumber, String naturalKey,
                                      Map<String, Object> payload) {
        return new ConnectorStaging(tenantId, runId, rowNumber,
                ColumnText.shortenKey(naturalKey, NATURAL_KEY_MAX_LENGTH), payload);
    }
}
