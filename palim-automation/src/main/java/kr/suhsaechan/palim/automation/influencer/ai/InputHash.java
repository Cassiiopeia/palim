package kr.suhsaechan.palim.automation.influencer.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * AI 재호출 여부를 가르는 입력 지문.
 *
 * <p>같은 채널을 다시 열었을 때 점수가 달라 보이면 그 순간 신뢰를 잃는다. 영상 목록·자막·댓글이
 * 그대로면 AI 를 부르지 않고 기존 점수를 쓴다 — 비용 절감보다 <b>재현성</b>이 목적이다.
 *
 * <p>프롬프트·루브릭·캠페인 버전도 지문에 넣는다. 기준이 바뀌면 같은 자료라도 다시 판단해야
 * 하기 때문이다.
 */
public final class InputHash {

    private InputHash() {
    }

    public static String of(String channelId, String campaignId, String promptVersion,
                            String rubricVersion, List<String> sources) {
        StringBuilder material = new StringBuilder()
                .append(channelId).append('')
                .append(campaignId).append('')
                .append(promptVersion).append('')
                .append(rubricVersion).append('');
        // 정렬해서 넣는다 — 수집 순서가 달라졌다는 이유로 재호출하지 않는다.
        sources.stream().filter(java.util.Objects::nonNull).sorted()
                .forEach(source -> material.append(source).append(''));

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }
}
