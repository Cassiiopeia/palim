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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 실패한 행 1건.
 *
 * <p>실패해도 원본 행을 통째로 남긴다. 행 번호와 에러 코드만 남기면 사람이 원본 파일을 다시
 * 열어 대조해야 하는데, 원천이 API 라면 그 시점 데이터를 다시 볼 방법이 아예 없다.
 *
 * <p>{@code errorCode} 를 enum 이 아니라 문자열로 두는 이유는 여기 담기는 값이
 * {@code ErrorCode.name()} 이라서다 — 코드가 늘어도 이 테이블은 그대로다.
 */
@Getter
@Entity
@Table(name = "connector_run_error")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorRunError extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID runId;

    /** 원천 기준 행 번호(1부터). 사람이 파일에서 찾아갈 수 있는 유일한 좌표다. */
    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> sourceRow;

    @Column(nullable = false, length = 50)
    private String errorCode;

    @Column(length = 1000)
    private String message;

    private ConnectorRunError(UUID tenantId, UUID runId, int rowNumber,
                              Map<String, Object> sourceRow, String errorCode, String message) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.runId = runId;
        this.rowNumber = rowNumber;
        this.sourceRow = sourceRow;
        this.errorCode = errorCode;
        this.message = message;
    }

    /** {@code message} 컬럼 길이. 초과하면 PostgreSQL 이 22001 로 트랜잭션을 중단시킨다. */
    private static final int MESSAGE_MAX_LENGTH = 1000;

    /**
     * 실패 행 기록.
     *
     * <p>메시지를 반드시 잘라서 넣는다. 중첩 원인이 붙은 JDBC·HTTP 예외 메시지는 1000자를
     * 쉽게 넘는데, 그대로 저장하면 <b>실패 행을 기록하려다 실행 전체가 죽는다.</b> 부분 실패를
     * 허용하려고 만든 테이블이 부분 실패를 없애는 셈이 된다.
     */
    public static ConnectorRunError of(UUID tenantId, UUID runId, int rowNumber,
                                       Map<String, Object> sourceRow, String errorCode,
                                       String message) {
        return new ConnectorRunError(tenantId, runId, rowNumber, sourceRow, errorCode,
                ColumnText.truncate(message, MESSAGE_MAX_LENGTH));
    }
}
