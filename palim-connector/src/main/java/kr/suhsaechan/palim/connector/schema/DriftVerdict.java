package kr.suhsaechan.palim.connector.schema;

import java.util.List;

/**
 * 드리프트 판정.
 *
 * @param blocking {@code true} 면 적재하지 않고 중단한다. 사람이 새 매핑 버전을 확정해야 재개된다
 * @param removed  확정 당시에는 있었으나 지금 없는 필드
 * @param added    확정 당시에는 없었으나 지금 있는 필드. {@code attributes} 로 보존된다
 * @param summary  사람이 읽을 요약. 알림과 실행 이력에 그대로 실린다
 */
public record DriftVerdict(boolean blocking, List<String> removed, List<String> added,
                           String summary) {

    public static DriftVerdict clean() {
        return new DriftVerdict(false, List.of(), List.of(), "");
    }
}
