package kr.suhsaechan.palim.connector.script;

import java.util.List;
import java.util.Map;

/**
 * 후처리 스크립트가 돌고 난 결과.
 *
 * <p>실패를 <b>예외로 던지지 않는다.</b> 스크립트가 죽은 것은 우리 코드의 오류가 아니라
 * 사장님이 쓴 글의 문제이고, 그 사유를 <b>화면에 그대로 보여줘야</b> 고칠 수 있다. 예외로
 * 던지면 어딘가에서 「처리 중 오류가 발생했습니다」로 뭉개진다.
 *
 * @param status  돌아간 결과
 * @param rows    스크립트가 돌려준 행. 실패면 비어 있다
 * @param message 사람이 읽을 사유. 스크립트가 stderr 로 남긴 말이 여기 온다
 * @param elapsedMs 걸린 시간. 느려지는 것을 눈치채려면 남아 있어야 한다
 */
public record PostScriptResult(Status status, List<Map<String, Object>> rows, String message,
                               long elapsedMs) {

    public enum Status { SUCCEEDED, FAILED, TIMEOUT }

    public static PostScriptResult succeeded(List<Map<String, Object>> rows, String message,
                                             long elapsedMs) {
        return new PostScriptResult(Status.SUCCEEDED, List.copyOf(rows), message, elapsedMs);
    }

    public static PostScriptResult failed(String message, long elapsedMs) {
        return new PostScriptResult(Status.FAILED, List.of(), message, elapsedMs);
    }

    public static PostScriptResult timedOut(String message, long elapsedMs) {
        return new PostScriptResult(Status.TIMEOUT, List.of(),
                message == null ? "시간 안에 끝나지 않아 끊었습니다." : message, elapsedMs);
    }

    public boolean isSucceeded() {
        return status == Status.SUCCEEDED;
    }
}
