package kr.suhsaechan.palim.connector.write;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.connector.run.RunMode;
import org.springframework.stereotype.Component;

/**
 * 실행 모드에 맞는 적재기 선택.
 *
 * <p>오케스트레이터가 {@code if (mode == TEST)} 로 분기하면 적재기가 늘 때마다 그 분기를
 * 고쳐야 한다. 등록만으로 붙도록 둔다.
 */
@Component
public class WriterSelector {

    private final Map<RunMode, RecordWriter> writers = new EnumMap<>(RunMode.class);

    public WriterSelector(List<RecordWriter> recordWriters) {
        recordWriters.forEach(writer -> writers.put(writer.mode(), writer));
    }

    public RecordWriter of(RunMode mode) {
        RecordWriter writer = writers.get(mode);
        if (writer == null) {
            throw new IllegalStateException("적재기가 없는 실행 모드: " + mode);
        }
        return writer;
    }
}
