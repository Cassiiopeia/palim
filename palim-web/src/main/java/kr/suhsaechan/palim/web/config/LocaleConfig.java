package kr.suhsaechan.palim.web.config;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * 요청 밖에서 조립되는 문구의 언어를 한국어로 못박는다.
 *
 * <p>화면이 전부 한국어인 제품인데, <b>누가 실행했느냐에 따라 언어가 갈렸다.</b>
 *
 * <pre>
 * 사람이 화면에서 누름  →  브라우저가 한국어를 요청  →  한글 문구
 * 매일 자동으로 돎      →  요청이 없으니 서버 기본값  →  영문 문구
 * </pre>
 *
 * <p>실패 사유는 <b>기록으로 남는다.</b> 그래서 같은 목록에 한글과 영문이 섞이고, 어제 것은
 * 읽히는데 오늘 것은 안 읽히는 상태가 된다. 컨테이너의 기본 언어가 무엇인지에 따라 결과가
 * 달라지는 것도 문제다 — 서버를 옮기면 조용히 바뀐다.
 *
 * <p>브라우저가 언어를 말하면 그쪽을 따른다. 여기서 정하는 것은 <b>말해 주지 않았을 때</b>의
 * 기본값뿐이다.
 */
@Slf4j
@Configuration
public class LocaleConfig {

    @PostConstruct
    void useKorean() {
        LocaleContextHolder.setDefaultLocale(Locale.KOREAN);
        log.info("요청 밖 기본 언어를 한국어로 둡니다 — 자동 수집·대조가 남기는 문구가 화면과 같은 말이어야 합니다");
    }
}
