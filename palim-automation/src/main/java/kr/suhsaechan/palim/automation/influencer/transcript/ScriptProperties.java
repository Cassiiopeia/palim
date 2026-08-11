package kr.suhsaechan.palim.automation.influencer.transcript;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * py 스크립트 실행 환경.
 *
 * <p>경로와 인터프리터는 배포 형태(도커 이미지 레이아웃)에 묶인 값이라 런타임 설정이 아니라
 * 여기 둔다 — 화면에서 바꿀 성질이 아니다.
 *
 * @param pythonExecutable 도커 이미지에 설치된 인터프리터
 * @param directory        {@code scripts/} 위치
 * @param timeoutSeconds   초과 시 {@code destroyForcibly}
 */
@ConfigurationProperties(prefix = "palim.scripts")
public record ScriptProperties(String pythonExecutable, String directory, int timeoutSeconds) {

    public ScriptProperties {
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
