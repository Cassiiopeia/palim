package kr.suhsaechan.palim.connector.source;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 읽기 컨텍스트.
 *
 * @param file      UPLOAD 일 때의 임시 파일. 다른 유형이면 {@code null}
 * @param headerRow 헤더 행 번호(1부터)
 * @param cursor    INCREMENTAL 일 때 이 값 이후만 가져온다. FULL 이면 {@code null}
 * @param config    원천별 비민감 설정(API URL·응답 경로 등). 비밀값은 여기 없다
 */
public record SourceContext(UUID connectorId, Path file, int headerRow, String cursor,
                            Map<String, Object> config) {

    /** 업로드 원천용 간편 생성. */
    public static SourceContext ofUpload(UUID connectorId, Path file, int headerRow) {
        return new SourceContext(connectorId, file, headerRow, null, Map.of());
    }
}
