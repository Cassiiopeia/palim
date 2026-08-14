package kr.suhsaechan.palim.logging;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 기동할 때 로그 설정이 실제로 어떻게 잡혔는지 한 줄로 남긴다.
 *
 * <p><b>왜 필요한가</b> — 「DEBUG 로 남기기로 했다」와 「실제로 DEBUG 가 남는다」는 다르다.
 * 설정이 어긋나도 화면에는 아무 표시가 없고, 정작 문제가 났을 때 로그를 열어 보고서야 안다.
 * 그때는 이미 그 순간이 지나간 뒤다.
 *
 * <p>파일 경로와 쓰기 가능 여부도 함께 남긴다. 로그 디렉터리에 쓰지 못해 <b>기동 자체가 막힌</b>
 * 적이 있어서, 「지금 어디에 쓰고 있는가」를 로그 첫머리에서 확인할 수 있어야 한다.
 */
@Slf4j
@Component
@Profile("prod")
public class LoggingStartupReporter {

    private static final String LOG_DIR_PROPERTY = "PALIM_LOG_DIR";
    private static final String DEFAULT_LOG_DIR = "/mnt/palim/logs";

    @PostConstruct
    void report() {
        Path dir = Path.of(System.getenv().getOrDefault(LOG_DIR_PROPERTY, DEFAULT_LOG_DIR));

        log.info("로그 설정 — 경로={} 존재={} 쓰기가능={} 우리코드DEBUG={}",
                dir, Files.isDirectory(dir), isWritable(dir), log.isDebugEnabled());

        // 이 줄이 파일에 보이면 DEBUG 가 실제로 남고 있다는 뜻이다. 안 보이면 설정이 안 먹은 것이다.
        log.debug("로그 확인 — 이 줄이 보이면 DEBUG 가 파일까지 도달한다");
    }

    private boolean isWritable(Path dir) {
        try {
            return Files.isWritable(dir);
        } catch (SecurityException e) {
            // 확인조차 막힌 경우다. 기동을 멈출 일은 아니지만 남겨 둔다.
            log.warn("로그 디렉터리 쓰기 여부를 확인하지 못했습니다 — 경로={}", dir, e);
            return false;
        }
    }
}
