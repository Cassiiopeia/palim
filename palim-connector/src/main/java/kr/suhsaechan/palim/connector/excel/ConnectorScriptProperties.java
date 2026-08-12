package kr.suhsaechan.palim.connector.excel;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * py 스크립트 실행 환경.
 *
 * <p>기존 {@code palim.scripts} 설정을 그대로 읽는다. 연동 전용 prefix 를 따로 두면 설정
 * 원본이 둘로 갈리고, 도커 이미지 레이아웃이 바뀔 때 한쪽만 고쳐져 어긋난다.
 *
 * <p>{@code palim-automation} 에도 같은 prefix 를 읽는 record 가 있다. 모듈 의존 방향이
 * {@code connector → automation} 이 아니라 반대라서 재사용할 수 없어 별도로 둔다 — 값의
 * 원본은 여전히 하나다.
 *
 * @param pythonExecutable 도커 이미지에 설치된 인터프리터
 * @param directory        {@code scripts/} 위치
 * @param timeoutSeconds   초과 시 {@code destroyForcibly}. 좀비 프로세스가 쌓이면 서버가 죽는다
 */
@ConfigurationProperties(prefix = "palim.scripts")
public record ConnectorScriptProperties(String pythonExecutable, String directory,
                                        int timeoutSeconds) {

    public ConnectorScriptProperties {
        if (pythonExecutable == null || pythonExecutable.isBlank()) {
            pythonExecutable = "python3";
        }
        if (directory == null || directory.isBlank()) {
            directory = "scripts";
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 90;
        }
    }
}
