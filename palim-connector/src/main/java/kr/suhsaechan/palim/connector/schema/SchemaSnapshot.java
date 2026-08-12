package kr.suhsaechan.palim.connector.schema;

import java.util.List;

/**
 * 매핑 확정 시점의 원천 필드 목록.
 *
 * <p>{@code connector_mapping.source_schema} 에 저장되어 이후 모든 실행의 대조 기준이 된다.
 * 매핑 버전마다 하나씩 있으므로 "그때는 어떤 양식이었나"를 나중에도 확인할 수 있다.
 */
public record SchemaSnapshot(List<String> fields) {

    /** 확정 이력이 없는 첫 실행. 대조할 기준이 없으므로 막지 않는다. */
    public boolean isEmpty() {
        return fields == null || fields.isEmpty();
    }
}
