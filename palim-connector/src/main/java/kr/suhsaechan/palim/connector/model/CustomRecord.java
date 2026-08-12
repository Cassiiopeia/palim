package kr.suhsaechan.palim.connector.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.connector.support.ColumnText;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 커스텀 모델의 데이터 1행.
 *
 * <p>모델마다 테이블을 만들지 않는다. 런타임 DDL 은 마이그레이션 이력과 어긋나고 권한도
 * 넓어져야 하는데, 그 대가로 얻는 것이 컬럼 조회 속도뿐이다. 대신 {@code payload} 에
 * GIN 인덱스를 걸어 JSONB 조건 검색을 받는다.
 *
 * <p>{@code naturalKey} 는 재실행 안전성의 전부다. 이 값이 같으면 같은 행으로 보고 갱신한다.
 */
@Getter
@Entity
@Table(name = "custom_record")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomRecord extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID targetModelId;

    /** 어느 실행이 마지막으로 이 행을 썼는가. 되돌리기와 원인 추적의 출발점이다. */
    private UUID runId;

    @Column(nullable = false, length = 500)
    private String naturalKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    private CustomRecord(UUID tenantId, UUID targetModelId, UUID runId, String naturalKey,
                         Map<String, Object> payload) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.targetModelId = targetModelId;
        this.runId = runId;
        this.naturalKey = naturalKey;
        this.payload = payload;
    }

    /** {@code natural_key} 컬럼 길이. 유니크 인덱스의 일부라 축약 방식이 중요하다. */
    private static final int NATURAL_KEY_MAX_LENGTH = 500;

    /**
     * 커스텀 모델 적재.
     *
     * <p>자연키는 유니크 인덱스에 걸려 있어 <b>절단이 곧 데이터 손실</b>이다. 앞부분이 같은
     * 서로 다른 행이 하나로 합쳐져 조용히 사라진다. 해시를 붙여 유일성을 지킨다.
     */
    public static CustomRecord of(UUID tenantId, UUID targetModelId, UUID runId, String naturalKey,
                                  Map<String, Object> payload) {
        return new CustomRecord(tenantId, targetModelId, runId,
                ColumnText.shortenKey(naturalKey, NATURAL_KEY_MAX_LENGTH), payload);
    }

    /** 같은 자연키로 다시 들어온 경우. 행을 새로 만들지 않고 내용만 갈아 끼운다. */
    public void replacePayload(UUID runId, Map<String, Object> payload) {
        this.runId = runId;
        this.payload = payload;
    }
}
