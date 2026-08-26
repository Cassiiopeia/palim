package kr.suhsaechan.palim.notification.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

/**
 * 어디로, 무엇을, 언제 보낼 것인가.
 *
 * <p>한 줄만 있는 표다. 보내는 곳이 여럿일 이유가 아직 없고, 여럿을 허용하면 「어느 것이
 * 쓰이는가」 를 화면과 코드가 각자 판단하게 된다.
 *
 * <p><b>비밀번호는 여기 없다.</b> 이 객체는 화면이 통째로 읽어 그리는 것이라, 비밀번호가 칸으로
 * 들어가는 순간 화면·감사 기록·직렬화로 새는 길이 한꺼번에 열린다.
 *
 * <p><b>설정을 하나씩 저장하지 않는 이유</b> — 메일 서버 값 다섯은 함께 있어야 뜻이 선다.
 * 하나씩 저장하면 「절반만 들어간」 중간 상태가 생기는데, 그 상태는 화면만 봐서는 정상과
 * 구분되지 않는다.
 */
@Entity
@Getter
@Table(name = "delivery_setting")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliverySetting extends BaseTimeEntity {

    /** 아주 느슨하게만 본다. 형식이 맞아도 못 받는 주소는 얼마든지 있으므로 오타만 거른다. */
    private static final Pattern ADDRESS = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private static final int MAX_RECIPIENTS = 10;

    @Id
    private UUID id;

    @Column(length = 200)
    private String smtpHost;

    @Column(nullable = false)
    private int smtpPort;

    @Column(length = 200)
    private String smtpUsername;

    @Column(length = 200)
    private String fromAddress;

    @Column(nullable = false)
    private boolean useStartTls;

    /** 받는 사람. 쉼표로 나눈다. */
    @Column(nullable = false, length = 500)
    private String recipients;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MailScope mailScope;

    @Column(nullable = false)
    private int digestHour;

    @Column(nullable = false)
    private int digestMinute;

    @Version
    private Long version;

    private DeliverySetting(boolean withDefaults) {
        this.id = UuidV7.generate();
        this.smtpPort = 587;
        this.useStartTls = true;
        this.recipients = "";
        this.mailScope = MailScope.DIGEST_ONLY;
        this.digestHour = 7;
        this.digestMinute = 30;
    }

    /** 아직 아무것도 넣지 않은 상태. 이때는 메일이 나가지 않고 쌓인다. */
    public static DeliverySetting createDefault() {
        return new DeliverySetting(true);
    }

    /**
     * 메일 서버를 바꾼다.
     *
     * <p>다섯 값을 <b>함께</b> 받는 이유는 위 주석에 적은 그대로다 — 절반만 저장되면 화면은
     * 정상으로 보이는데 발송만 조용히 실패한다.
     */
    public void changeSmtp(String host, int port, String username, String from, boolean startTls) {
        if (port < 1 || port > 65535) {
            throw new BusinessException(ErrorCode.INVALID_DELIVERY_SCHEDULE, "포트", port);
        }
        if (StringUtils.hasText(from) && !ADDRESS.matcher(from.trim()).matches()) {
            throw new BusinessException(ErrorCode.INVALID_MAIL_RECIPIENT, from.trim());
        }
        this.smtpHost = trimOrNull(host);
        this.smtpPort = port;
        this.smtpUsername = trimOrNull(username);
        this.fromAddress = trimOrNull(from);
        this.useStartTls = startTls;
    }

    /** 받는 사람을 바꾼다. 쉼표로 나눈 글이 들어온다. */
    public void changeRecipients(String csv) {
        List<String> parsed = parseRecipients(csv);
        if (parsed.size() > MAX_RECIPIENTS) {
            throw new BusinessException(ErrorCode.INVALID_MAIL_RECIPIENT,
                    "받는 사람은 %d명까지입니다".formatted(MAX_RECIPIENTS));
        }
        for (String address : parsed) {
            if (!ADDRESS.matcher(address).matches()) {
                // 잘못 적힌 주소 하나가 발송 전체를 실패로 만든다. 저장할 때 걸러야 «보냈는데
                // 안 왔다» 를 며칠 뒤에 알아채는 일이 없다.
                throw new BusinessException(ErrorCode.INVALID_MAIL_RECIPIENT, address);
            }
        }
        this.recipients = String.join(",", parsed);
    }

    public void changeMailScope(MailScope scope) {
        this.mailScope = scope;
    }

    /** 하루 한 통을 보내는 시각. */
    public void changeDigestTime(int hour, int minute) {
        if (hour < 0 || hour > 23) {
            throw new BusinessException(ErrorCode.INVALID_DELIVERY_SCHEDULE, "시", hour);
        }
        if (minute < 0 || minute > 59) {
            throw new BusinessException(ErrorCode.INVALID_DELIVERY_SCHEDULE, "분", minute);
        }
        this.digestHour = hour;
        this.digestMinute = minute;
    }

    /** 받는 사람 목록. */
    public List<String> recipientList() {
        return parseRecipients(recipients);
    }

    public LocalTime digestTime() {
        return LocalTime.of(digestHour, digestMinute);
    }

    /**
     * 메일을 보낼 수 있는 상태인가.
     *
     * <p>비밀번호가 들어 있는지는 여기서 모른다 — 그 값은 다른 곳에 있다. 부르는 쪽이 함께
     * 확인한다.
     */
    public boolean isMailConfigured() {
        return StringUtils.hasText(smtpHost)
                && StringUtils.hasText(fromAddress)
                && !recipientList().isEmpty();
    }

    private static List<String> parseRecipients(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private static String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
