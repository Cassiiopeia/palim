package kr.suhsaechan.palim.reconcile.engine;

import java.util.List;
import kr.suhsaechan.palim.common.config.ConfigDefinition;
import kr.suhsaechan.palim.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

/**
 * 정기 대조를 <b>언제</b> 돌릴 것인가.
 *
 * <p>지금까지 이 값은 코드에 박힌 기본값 하나였고, 그것을 가리키는 설정 키는 <b>어떤 설정
 * 파일에도 선언돼 있지 않았다.</b> 바꾸려면 존재하지 않는 키 이름을 알아내 비밀 설정을 고치고
 * 다시 배포해야 했다 — 재기동이 아니라 <b>재배포</b>다.
 *
 * <p>설정으로 옮기면 범위 확인·변경 이력이 함께 따라온다.
 */
@Component
public class ReconcileScheduleConfigDefinitions implements ConfigDefinitionProvider {

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(
                ConfigDefinition.integer(ReconcileScheduleKeys.HOUR, 7, 0, 23,
                        ReconcileScheduleKeys.CATEGORY, "대조 실행 시각(시)",
                        "매일 이 시각에 두 시스템의 재고를 맞춰 봅니다. "
                                + "수집이 끝난 뒤여야 하므로, 가져오는 시각보다 늦게 잡습니다.", 1),
                ConfigDefinition.integer(ReconcileScheduleKeys.MINUTE, 0, 0, 59,
                        ReconcileScheduleKeys.CATEGORY, "대조 실행 시각(분)",
                        "위 시각의 분. 예를 들어 7시 0분이면 매일 아침 7시에 돕니다.", 2));
    }
}
