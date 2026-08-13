package kr.suhsaechan.palim.reconcile.engine;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.notification.NotificationType;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 알릴 것을 내보낸다.
 *
 * <p>무엇을 알릴지는 {@link ReconcileAlertPolicy} 가 정한다. 이 클래스는 <b>보내기만</b> 한다 —
 * 판단이 발송 코드에 섞이면 「왜 이건 안 왔지」를 확인하려고 발송 경로를 뒤져야 한다.
 *
 * <p>같은 날 같은 대조로는 한 번만 보낸다. 하루에 여러 번 맞춰 보는 일이 있는데 그때마다
 * 알리면, 손대지 않은 같은 차이가 반복해서 온다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileNotifier {

    /** 같은 대조의 알림을 억제하는 기간. 하루에 한 번이면 충분하다. */
    private static final Duration SUPPRESS_WITHIN = Duration.ofHours(20);

    /** 본문에 담을 차이 개수. 전부 넣으면 알림이 스크롤되고 그러면 아무도 안 읽는다. */
    private static final int SAMPLE_LIMIT = 5;

    private final OutboxService outbox;

    public void notifyMismatch(ReconcileDefinition definition, ReconcileRun run,
                               List<ReconcileDiff> alertable) {
        String dedupeKey = "reconcile:" + definition.getCode() + ":" + run.getBaseAt();

        List<Map<String, Object>> samples = alertable.stream()
                .limit(SAMPLE_LIMIT)
                .map(diff -> Map.<String, Object>of(
                        "unit", diff.getUnitCode(),
                        "left", diff.getLeftQuantity(),
                        "right", diff.getRightQuantity(),
                        "delta", diff.getDelta()))
                .toList();

        outbox.enqueueIfNotRecent(NotificationType.STOCK_MISMATCH, dedupeKey, SUPPRESS_WITHIN,
                        Map.of(
                                "definition", definition.getName(),
                                "leftSource", definition.getLeftSource(),
                                "rightSource", definition.getRightSource(),
                                "baseAt", run.getBaseAt().toString(),
                                "count", alertable.size(),
                                "samples", samples))
                .ifPresentOrElse(
                        sent -> log.info("재고 불일치 알림 — definition={} {}건",
                                definition.getCode(), alertable.size()),
                        () -> log.debug("이미 알린 건이라 건너뛴다 — {}", dedupeKey));
    }
}
