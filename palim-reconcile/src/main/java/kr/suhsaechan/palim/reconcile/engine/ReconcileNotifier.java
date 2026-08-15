package kr.suhsaechan.palim.reconcile.engine;

import java.time.Duration;
import java.util.List;
import kr.suhsaechan.palim.notification.NotificationType;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.payload.ReconcileBlockedPayload;
import kr.suhsaechan.palim.notification.payload.ReconcileMismatchPayload;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알릴 것을 내보낸다.
 *
 * <p>무엇을 알릴지는 {@link ReconcileAlertPolicy} 가 정한다. 이 클래스는 <b>보내기만</b> 한다 —
 * 판단이 발송 코드에 섞이면 「왜 이건 안 왔지」를 확인하려고 발송 경로를 뒤져야 한다.
 *
 * <p>같은 날 같은 대조로는 한 번만 보낸다. 하루에 여러 번 맞춰 보는 일이 있는데 그때마다
 * 알리면, 손대지 않은 같은 차이가 반복해서 온다.
 *
 * <p><b>보내는 메서드마다 트랜잭션을 연다.</b> 대기열에 넣는 쪽이
 * {@code propagation = MANDATORY} 라 바깥 트랜잭션이 없으면 예외로 튄다. 이 클래스를 부르는
 * 스케줄러는 트랜잭션 밖이라, 이것이 없으면 알림을 넣으려는 순간 터지고 <b>그 예외를 스케줄러가
 * 「자동 대조 실패」 로 삼켜</b> 알림 경로 전체가 조용히 죽는다 — 실제로 그 상태였다. 차이를
 * 찾아 놓고도 아무에게도 못 알리는데, 로그에는 대조가 실패한 것처럼만 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileNotifier {

    /** 같은 대조의 알림을 억제하는 기간. 하루에 한 번이면 충분하다. */
    private static final Duration SUPPRESS_WITHIN = Duration.ofHours(20);

    /**
     * 「막혀 있다」 를 다시 알리기까지의 기간.
     *
     * <p>차이 알림보다 훨씬 길다. 막힌 상태는 <b>고칠 때까지 매일 같은 모양</b>이라 하루마다
     * 부르면 그 알림이 배경음이 되고, 그러면 정작 고쳐야 할 때 안 보인다.
     *
     * <p>그렇다고 한 번만 부르고 마는 것도 옳지 않다 — 놓치면 그대로 묻힌다. 일주일에 한 번은
     * 「아직 안 고쳐졌습니다」 라고 말한다.
     */
    private static final Duration BLOCKED_SUPPRESS_WITHIN = Duration.ofDays(7);

    /** 본문에 담을 차이 개수. 전부 넣으면 알림이 스크롤되고 그러면 아무도 안 읽는다. */
    private static final int SAMPLE_LIMIT = 5;

    private final OutboxService outbox;

    @Transactional
    public void notifyMismatch(ReconcileDefinition definition, ReconcileRun run,
                               List<ReconcileDiff> alertable) {
        String dedupeKey = "reconcile:" + definition.getCode() + ":" + run.getBaseAt();

        // 받는 쪽이 읽는 것과 «같은 모양» 으로 보낸다.
        //
        // 예전에는 이름만 맞춘 Map 을 STOCK_MISMATCH 로 보냈다. 그 종류는 동결 도메인의
        // 「한 시스템 안에서 기준값과 이력이 안 맞음」 이라 칸 이름이 하나도 겹치지 않았고,
        // 받는 쪽은 그 형식으로 읽어 «재고 0개 / 이력 0개» 라는 빈 알림을 그렸다.
        // 오류가 아니라 성공한 것처럼 보이는 빈 값이라 아무도 이상하다고 여기지 않는다.
        List<ReconcileMismatchPayload.Sample> samples = alertable.stream()
                .limit(SAMPLE_LIMIT)
                .map(diff -> new ReconcileMismatchPayload.Sample(
                        diff.getUnitCode(), diff.getLeftQuantity(),
                        diff.getRightQuantity(), diff.getDelta()))
                .toList();

        outbox.enqueueIfNotRecent(NotificationType.RECONCILE_MISMATCH, dedupeKey, SUPPRESS_WITHIN,
                        new ReconcileMismatchPayload(
                                definition.getName(),
                                definition.getLeftSource(),
                                definition.getRightSource(),
                                run.getBaseAt(),
                                alertable.size(),
                                samples))
                .ifPresentOrElse(
                        sent -> log.info("재고 대조 차이 알림 — definition={} {}건",
                                definition.getCode(), alertable.size()),
                        () -> log.debug("이미 알린 건이라 건너뛴다 — {}", dedupeKey));
    }

    /**
     * 대조가 <b>여러 날 연속으로 막혀 있다.</b>
     *
     * <p>하루 못 돈 것은 여기 오지 않는다 — 수집이 늦으면 기준 시각이 어긋나고 다음 회차에
     * 저절로 풀린다. 문제는 <b>영영 안 풀리는 실패도 똑같이 생겼다</b>는 것이다. 지금까지
     * 자동 대조는 실패하면 로그만 남기고 넘어갔고, 그래서 몇 주를 안 돌아도 아무도 몰랐다.
     *
     * <p>중복 억제 열쇠에 <b>연속 횟수를 넣지 않는다.</b> 넣으면 실패할 때마다 열쇠가 달라져
     * 매일 알림이 간다 — 막으려던 그 소음이 그대로 돌아온다.
     *
     * <p>부르는 쪽은 문턱을 넘은 <b>매 회차마다</b> 부른다. 소음을 막는 일은 전적으로 여기
     * 억제 기간이 맡는다 — 부르는 쪽에서 「한 번만」 을 판정하려 하면 셈이 한 번이라도
     * 건너뛰는 순간 영영 조용해진다.
     */
    @Transactional
    public void notifyBlocked(ReconcileDefinition definition, ReconcileRun run, int failedDays) {
        String dedupeKey = "reconcile-blocked:" + definition.getCode();

        outbox.enqueueIfNotRecent(NotificationType.RECONCILE_BLOCKED, dedupeKey,
                        BLOCKED_SUPPRESS_WITHIN,
                        new ReconcileBlockedPayload(
                                definition.getName(),
                                // 이미 사람 말로 풀린 문장이다. 여기서 다시 손대지 않는다.
                                run.getMessage() == null ? "" : run.getMessage(),
                                failedDays,
                                run.getStartedAt()))
                .ifPresentOrElse(
                        sent -> log.warn("대조가 막혀 있다고 알린다 — definition={} {}일째",
                                definition.getCode(), failedDays),
                        () -> log.debug("이미 알린 건이라 건너뛴다 — {}", dedupeKey));
    }
}
