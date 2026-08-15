package kr.suhsaechan.palim.connector.run;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기동할 때 <b>「실행 중」인 채로 굳은 실행</b>을 정리한다.
 *
 * <p>같은 연동이 겹쳐 도는 것을 막으려고, 실행 중인 것이 있으면 새 실행을 거부한다. 그 판단은
 * 실행 기록의 상태로 한다.
 *
 * <p>문제는 <b>앱이 실행 도중에 내려갈 때</b>다. 배포는 컨테이너를 지웠다 다시 만들고, 그때
 * 돌고 있던 실행은 끝났다는 기록을 남기지 못한다. 그 기록은 영원히 「실행 중」으로 남아
 * <b>그 연동을 잠근다.</b> 사람이 화면에서 풀 방법이 없어, 배포 한 번에 연동 하나가 죽는다.
 *
 * <p>실제로 그랬다. 오늘 배포를 여러 번 했더니 이카운트 연동이 「이미 실행 중입니다」만
 * 반복하며 아무것도 할 수 없는 상태가 됐다.
 *
 * <p><b>기동한 순간, 남아 있는 「실행 중」은 전부 죽은 것이다.</b> 그 실행을 하던 프로세스는
 * 이미 없다. 그러니 여기서 실패로 닫는다 — 조용히 지우지 않고 <b>실패로 남기는</b> 이유는,
 * 그 시각에 무엇을 하려다 끊겼는지가 나중에 원인을 찾는 단서이기 때문이다.
 *
 * <p>이 판단이 성립하는 전제는 <b>인스턴스가 하나</b>라는 것이다. 여러 대로 늘리면 다른 대가
 * 돌리는 중일 수 있으므로, 그때는 «누가 언제까지 잡고 있다» 는 임차 방식으로 바꿔야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleRunRecovery {

    private final ConnectorRunRepository runRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void closeStaleRuns() {
        List<ConnectorRun> stale = runRepository.findByStatus(RunStatus.RUNNING);
        if (stale.isEmpty()) {
            log.debug("기동 점검 — 「실행 중」인 채로 남은 실행이 없습니다");
            return;
        }

        stale.forEach(run -> {
            // 어느 연동이 얼마나 오래 잠겨 있었는지 남긴다. 「배포 때문이었다」 를 나중에
            // 확인하려면 시작 시각이 있어야 한다.
            log.warn("기동 점검 — 「실행 중」으로 굳은 실행을 실패로 닫습니다. "
                            + "실행id={} 커넥터id={} 시작={} (앱이 실행 도중에 내려갔습니다)",
                    run.getId(), run.getConnectorId(), run.getStartedAt());
            run.fail("앱이 실행 도중에 내려가 끝맺지 못했습니다. 다시 실행하세요.");
        });
        runRepository.saveAll(stale);

        log.warn("기동 점검 — 굳어 있던 실행 {}건을 정리했습니다. 해당 연동을 다시 실행할 수 있습니다",
                stale.size());
    }
}
