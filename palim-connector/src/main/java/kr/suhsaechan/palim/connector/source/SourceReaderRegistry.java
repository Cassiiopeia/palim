package kr.suhsaechan.palim.connector.source;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.define.SourceType;
import org.springframework.stereotype.Component;

/**
 * 원천 유형 → 어댑터.
 *
 * <p>새 원천(DB·FTP·이메일·웹훅)은 {@link SourceReader} 구현체를 빈으로 등록하는 것만으로
 * 붙는다. 오케스트레이터에 분기를 추가할 필요가 없다.
 */
@Component
public class SourceReaderRegistry {

    private final Map<SourceType, SourceReader> readers = new EnumMap<>(SourceType.class);

    public SourceReaderRegistry(List<SourceReader> sourceReaders) {
        sourceReaders.forEach(reader -> readers.put(reader.type(), reader));
    }

    public SourceReader of(SourceType type) {
        SourceReader reader = readers.get(type);
        if (reader == null) {
            throw new BusinessException(ErrorCode.CONNECTOR_SOURCE_UNREACHABLE, type.name());
        }
        return reader;
    }
}
