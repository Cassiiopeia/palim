package kr.suhsaechan.palim.notification.delivery;

import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Properties;
import kr.suhsaechan.palim.notification.secret.NotificationSecretService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 메일을 실제로 보낸다.
 *
 * <h2>발송기를 빈으로 두지 않는 이유</h2>
 *
 * <p>세 가지가 겹친다.
 *
 * <ol>
 *   <li>자동 구성은 <b>설정 파일에 서버 주소가 있을 때만</b> 발송기를 만든다. 우리는 그 값을
 *       DB 에 두므로 빈이 아예 없고, 그것을 주입받는 순간 <b>통합 시험 전부가 기동 실패로
 *       한꺼번에 깨진다.</b></li>
 *   <li>발송기 빈이 있으면 상태 점검이 <b>실제 메일 서버에 접속</b>한다. 배포가 그 점검을
 *       기다리므로, 메일 서버가 잠깐 죽으면 <b>멀쩡한 앱의 배포가 실패한다.</b></li>
 *   <li>값이 DB 에 있으니 설정 바인딩이 애초에 성립하지 않는다.</li>
 * </ol>
 *
 * <p>그래서 저장된 값으로 <b>직접 조립</b>한다. 메신저 발송이 같은 이유로 같은 방식을 쓴다.
 *
 * <h2>비밀번호</h2>
 *
 * <p>보내는 순간에만 꺼내 쓰고 어디에도 남기지 않는다. <b>어떤 로그에도 넘기지 않는다</b> —
 * 파일 기록은 마스킹 없이 남고 운영은 30일치를 보관한다. 한 번 흘리면 30일간 서버에 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmtpMailSender {

    private static final int TIMEOUT_MILLIS = 10_000;

    private final DeliverySettingService settings;
    private final NotificationSecretService secrets;

    /** 지금 보낼 수 있는가. 못 보내면 큐에 남겨 두고 나중에 다시 본다. */
    public boolean isConfigured() {
        return settings.canSendMail();
    }

    /**
     * 한 통 보낸다.
     *
     * @param subject 제목. <b>이 화면의 존재 이유</b>다 — 열지 않고 판단하게 한다
     */
    public MailSendResult send(String subject, String body) {
        DeliverySetting setting = settings.get();
        List<String> to = setting.recipientList();
        if (!setting.isMailConfigured() || to.isEmpty()) {
            return MailSendResult.transientFailure("메일 서버가 아직 등록되지 않았습니다");
        }
        String password = secrets.find(NotificationSecretService.SMTP_PASSWORD).orElse(null);
        if (password == null) {
            return MailSendResult.transientFailure("메일 비밀번호가 등록되지 않았습니다");
        }

        try {
            JavaMailSenderImpl sender = assemble(setting, password);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(setting.getFromAddress());
            helper.setTo(to.toArray(String[]::new));
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);
            log.info("메일 발송 — 받는 사람 {}명", to.size());
            return MailSendResult.sent();
        } catch (MailAuthenticationException e) {
            // 다시 해도 안 된다. 계속 시도하면 계정이 잠긴다.
            return MailSendResult.permanentFailure("메일 서버 인증에 실패했습니다");
        } catch (MailParseException | jakarta.mail.MessagingException e) {
            return MailSendResult.permanentFailure("메일 내용을 만들지 못했습니다");
        } catch (MailSendException e) {
            // 주소가 틀렸는지, 서버가 잠깐 안 되는지가 여기 섞여 온다. 주소 문제면 다시 해도
            // 안 되지만, 잠깐의 문제를 영구 실패로 두면 그날 알림을 통째로 잃는다.
            return failureOf(e);
        } catch (RuntimeException e) {
            return MailSendResult.transientFailure(shortMessage(e));
        }
    }

    /**
     * 저장된 값으로 발송기를 만든다.
     *
     * <p>매번 만드는 이유 — 설정은 화면에서 바뀌고, 캐시해 두면 바꾼 값이 다음 재기동까지
     * 안 먹는다. 만드는 비용은 연결을 여는 비용에 비하면 없는 것이나 같다.
     */
    private JavaMailSenderImpl assemble(DeliverySetting setting, String password) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(setting.getSmtpHost());
        sender.setPort(setting.getSmtpPort());
        sender.setUsername(setting.getSmtpUsername());
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(setting.isUseStartTls()));
        // 시간 제한이 없으면 서버가 응답을 안 줄 때 중계가 그 자리에 멈춘다. 그러면 메일뿐
        // 아니라 그 뒤 대기분 전체가 함께 멎는다.
        props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MILLIS));
        props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MILLIS));
        props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MILLIS));
        // 통신 내용을 남기지 않는다 — 비밀번호가 그 안에 흐른다.
        props.put("mail.debug", "false");
        return sender;
    }

    private MailSendResult failureOf(MailSendException e) {
        String message = shortMessage(e);
        boolean addressProblem = message.contains("550") || message.contains("553")
                || message.contains("Invalid Addresses");
        return addressProblem
                ? MailSendResult.permanentFailure(message)
                : MailSendResult.transientFailure(message);
    }

    /** 사유를 짧게. 원문에 서버가 되돌린 값이 길게 붙어 이력 화면을 덮는다. */
    private String shortMessage(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
