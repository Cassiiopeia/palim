package kr.suhsaechan.palim.web.connector;

import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.BaseAtGranularity;
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
                                  int lastSuccess, int lastFailed,
                                  BaseAtGranularity baseAtGranularity,
                                  int fileMappingFields, String fileGuide) {

    /**
     * @param fileMappingFields 파일 길의 확정된 칸 수. 0이면 파일을 올려도 전 행이 실패하므로
     *                          화면이 올리기 전에 말해야 한다
     * @param fileGuide         파일을 어디서 어떻게 받는지. 급할 때 찾아다니지 않게 적어 둔다
     */
    public static ConnectorDetailView of(Connector connector, boolean mappingActive,
                                         Integer activeVersion, RunSummary lastRun,
                                         int fileMappingFields) {
        return new ConnectorDetailView(
                connector.getId(), connector.getName(), connector.getCode(),
                connector.getSourceType(), connector.getConnectionStatus(),
                mappingActive, activeVersion, connector.getScheduleCron(),
                lastRun == null ? null : lastRun.startedAt(),
                lastRun == null ? null : lastRun.status(),
                lastRun == null ? 0 : lastRun.successCount(),
                lastRun == null ? 0 : lastRun.failedCount(),
                connector.getBaseAtGranularity() == null
                        ? BaseAtGranularity.DAY : connector.getBaseAtGranularity(),
                fileMappingFields,
                connector.getFileGuide());
    }

    /**
     * 스스로 가져오는 연동인가.
     *
     * <p>원래 파일로 받는 연동에게 파일은 <b>정상 경로</b>이지 우회로가 아니다. 그쪽에
     * 「파일로 채우기」 를 또 두면 같은 일이 두 자리에 있어 어느 쪽이 진짜인지 흐려진다.
     */
    public boolean fetchesItself() {
        return sourceType != SourceType.UPLOAD;
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
