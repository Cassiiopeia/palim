package kr.suhsaechan.palim.connector.source.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 상대 시스템에 <b>물어서</b> 「어느 메뉴로 들어가 엑셀을 받나」 를 알아낸다.
 *
 * <p>파일로 대신 채우는 길은 <b>자동 수집이 깨진 날</b> 쓴다. 그때 메뉴를 찾아다니게 하면
 * 우회로가 우회로가 아니다. 그렇다고 <b>코드에 적어 두면 상대가 메뉴를 바꾼 날 거짓말이
 * 된다</b> — 사람은 그 거짓말을 믿고 없는 메뉴를 찾는다. 안 적어 둔 것보다 나쁘다.
 *
 * <p><b>계정은 서버 밖으로 나가지 않는다.</b> 매일 도는 수집과 같은 자리에서 같은 계정으로
 * 로그인하고, 화면에서 메뉴 글자만 읽어 온다. 상대가 메뉴를 바꾸면 다시 눌러 다시 읽으면 된다.
 *
 * <p><b>못 찾으면 지어내지 않는다.</b> 그럴듯한 경로를 지어내는 것이 이 기능에서 제일 나쁜
 * 결과다 — 사람이 그것을 믿고 헤매게 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuPathFinder {

    private final FormSessionClient client;
    private final MenuPathProbe probe;

    /**
     * @param config   연동의 원천 설정. 로그인 주소·조회 본문이 들어 있다
     * @param password 저장소에서 꺼낸 비밀번호. <b>이 값은 어디에도 남기지 않는다</b>
     * @return 바깥 메뉴부터 안쪽 순서. 못 찾으면 빈 목록
     */
    public List<String> find(Map<String, String> config, String userId, String password) {
        String screenCode = probe.screenCodeOf(config);
        if (screenCode.isBlank()) {
            log.debug("조회 본문에 화면 코드가 없어 메뉴를 찾지 않습니다");
            return List.of();
        }

        Map<String, String> cookies = new LinkedHashMap<>();
        try {
            FormSessionClient.LoginPage page = client.openLoginPage(
                    config.get("loginUrl"), config.getOrDefault("tokenField", "token"), cookies);
            FormSessionClient.Session session =
                    client.login(config, userId, password, cookies, page);
            // 조회 주소가 곧 화면 주소다. 메뉴는 그 화면을 감싼 틀에 들어 있다.
            String html = client.fetchPage(config.get("fetchUrl"), session);
            return probe.menuLabels(html, screenCode);
        } catch (RuntimeException e) {
            // 못 찾는 것은 실패가 아니다 — 안내를 손으로 적으면 되고, 그 길은 이미 있다.
            log.info("메뉴 경로를 읽지 못했습니다 — 화면코드={} 사유={}", screenCode, e.toString());
            return List.of();
        }
    }
}
