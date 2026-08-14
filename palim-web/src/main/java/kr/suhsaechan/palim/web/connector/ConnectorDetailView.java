package kr.suhsaechan.palim.web.connector;

import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.connector.define.ConnectionStatus;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.SourceType;
import org.springframework.util.StringUtils;

/**
 * 시스템 하나의 상태를 한 화면분으로 모은다.
 *
 * <p>엔티티를 그대로 화면에 넘기지 않는 이유는 <b>화면이 판단하지 않게</b> 하기 위해서다.
 * 「몇 시에 가져오는가」를 cron 문자열에서 화면이 파싱하기 시작하면 그 계산이 템플릿에 흩어진다.
 */
public record ConnectorDetailView(UUID id, String name, String code, SourceType sourceType,
                                  ConnectionStatus connectionStatus, boolean mappingActive,
                                  Integer activeVersion, String scheduleCron,
                                  Instant lastRunAt, String lastStatus,
                                  int lastSuccess, int lastFailed) {

    public static ConnectorDetailView of(Connector connector, boolean mappingActive,
                                         Integer activeVersion, RunSummary lastRun) {
        return new ConnectorDetailView(
                connector.getId(), connector.getName(), connector.getCode(),
                connector.getSourceType(), connector.getConnectionStatus(),
                mappingActive, activeVersion, connector.getScheduleCron(),
                lastRun == null ? null : lastRun.startedAt(),
                lastRun == null ? null : lastRun.status(),
                lastRun == null ? 0 : lastRun.successCount(),
                lastRun == null ? 0 : lastRun.failedCount());
    }

    public boolean connected() {
        return connectionStatus.isUsable();
    }

    public boolean scheduled() {
        return StringUtils.hasText(scheduleCron);
    }

    /**
     * 파일로 올리는 방식은 자동으로 돌지 않는다. 시각을 물어보면 정해 놓고 안 도는 상태가 되어
     * 「왜 안 오지」가 된다.
     */
    public boolean schedulable() {
        return sourceType != SourceType.UPLOAD;
    }

    /** 화면에 보여줄 시각. cron 을 사람이 읽는 형태로 되돌린다. */
    public String scheduleLabel() {
        if (!scheduled()) {
            return null;
        }
        String[] parts = scheduleCron.split(" ");
        if (parts.length < 3) {
            return scheduleCron;
        }
        return "매일 %s:%s".formatted(pad(parts[2]), pad(parts[1]));
    }

    public int scheduleHour() {
        return partAt(2);
    }

    public int scheduleMinute() {
        return partAt(1);
    }

    private int partAt(int index) {
        if (!scheduled()) {
            return index == 2 ? 6 : 0;
        }
        String[] parts = scheduleCron.split(" ");
        if (parts.length <= index) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String pad(String value) {
        return value.length() == 1 ? "0" + value : value;
    }
}
