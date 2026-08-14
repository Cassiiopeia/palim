# 재고 맞추기 — 길 보여주기 (계획 1/3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사장님이 「지금 뭘 해야 하는지」를 홈에서 보고, 붙여 둔 시스템을 한 자리에서 손볼 수 있게 한다.

**Architecture:** 준비 상태를 계산하는 `SetupService` 를 실제 상태로 고치고, 그것을 홈이 흡수한다.
커넥터 목록에 「연결」과 「칸 맞추기」를 나란히 놓아 어디가 막혔는지 보이게 하고, 시스템 하나의
모든 것을 모으는 상세 화면을 새로 만든다. 마지막에 메뉴를 정리한다 — **모든 화면이 갈 자리를
가진 뒤에** 메뉴에서 빼야 고아가 생기지 않는다.

**Tech Stack:** Java 25 · Spring Boot 4.1 · Thymeleaf + daisyUI 5 · PostgreSQL 14 · Testcontainers

## Global Constraints

- **PG14 문법만.** `NULLS NOT DISTINCT`·`MERGE` 금지. `INSERT ... ON CONFLICT` 사용
- `JdbcClient` 에 `Instant` 를 바인딩하지 않는다 — `timestamptz` 에는 `OffsetDateTime`. `count`·`sum` 은 `bigint` 이므로 record 가 `int` 면 `count(*)::int`
- 전 계층 `Instant`. 표시 직전에만 변환
- 예외는 `BusinessException` + `ErrorCode` 만. 새 예외 클래스 금지. 새 실패 유형은 `ErrorCode` enum 한 줄 + `errors.properties`/`errors_en.properties` 각 한 줄
- 사용자 화면 문구는 `ErrorMessageResolver.resolve(errorCode, messageArgs)` 로 만든다. `BusinessException.getMessage()` 는 로그용이다
- **동결 도메인 수정 금지**: `palim-sku`·`palim-order`·`palim-collector`·`palim-channel`·`palim-mapping`·`palim-incident`
- **공개 레포다.** 발주사 상호·브랜드·제품명, 서버 호스트·IP·포트·경로를 코드·문서·테스트·커밋 메시지 어디에도 쓰지 않는다. 테스트는 합성값으로
- 커밋 메시지에 AI 흔적(`Co-Authored-By`, `Generated with`, 🤖) 금지 — CI `guard` 잡이 검사한다
- 커밋 형식: `{이슈제목} : {타입} : {설명} {이슈URL}`
- 화면을 검사하는 테스트는 **반드시** `RenderAssertions.fullyRendered()` 를 함께 쓴다 (`palim-app/src/test/java/kr/suhsaechan/palim/integration/RenderAssertions.java`). 200 이어도 렌더 도중 끊기면 버튼과 사이드바가 사라진다
- 화면 테스트는 **자료가 없는 상태와 있는 상태 양쪽**으로 연다. 목록이 비면 행을 그리는 부분이 한 번도 실행되지 않는다
- Testcontainers 는 `postgres:14-alpine` 고정. 올리지 않는다
- 시작 전 `git pull --rebase`, 커밋 전 `git status` 로 낯선 변경 확인, **내가 건드린 경로만 명시해 스테이징**(`git add -A` 금지 — 다른 세션이 동시 작업 중일 수 있다)
- 현재 테스트 493건 통과. 브랜치 `develop`

## 빌드 · 테스트 실행

```bash
JAVA_HOME=/Users/suhsaechan/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
./gradlew build \
  -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

한 클래스만:

```bash
JAVA_HOME=... ./gradlew :palim-app:test --tests "*SetupStateIntegrationTest*" \
  -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

## File Structure

| 파일 | 책임 |
|---|---|
| `palim-web/.../setup/SetupService.java` (수정) | 준비 4단계의 **실제** 상태를 계산한다. 지금은 2·3·4단계가 「준비 중」으로 굳어 있다 |
| `palim-web/.../setup/SetupController.java` (수정) | `/setup` 을 홈으로 넘긴다 |
| `palim-web/.../HomeController.java` (수정) | 홈이 준비 상태와 오늘 결과를 함께 보여준다 |
| `palim-web/.../home/TodayReconcile.java` (신규) | 홈에 띄울 「오늘 결과」 한 줄 요약. 없으면 비어 있다 |
| `palim-web/.../home/HomeSummaryService.java` (신규) | 오늘 결과를 조회한다. `SetupService` 와 책임을 나눈다 |
| `palim-web/src/main/resources/templates/home.html` (수정) | 준비 중이면 다음 한 걸음, 끝났으면 오늘 결과 |
| `palim-web/.../connector/ConnectorSummary.java` (수정) | 목록 한 줄에 연결 상태·자동 수집을 담는다 |
| `palim-web/.../connector/ConnectorQueryService.java` (수정) | 위 두 칸을 조회에 추가 |
| `palim-web/.../connector/ConnectorDetailView.java` (신규) | 시스템별 상세 한 화면분 |
| `palim-web/.../connector/ConnectorDetailController.java` (신규) | `GET /connectors/{id}` · 자동 수집 시각 저장 |
| `palim-web/src/main/resources/templates/connector/detail.html` (신규) | 시스템별 상세 |
| `palim-web/src/main/resources/templates/connector/list.html` (수정) | 칸 추가 · 버튼 목적지 변경 |
| `palim-web/src/main/resources/templates/layout.html` (수정) | 메뉴 재구성 |
| `palim-app/src/test/.../integration/SetupStateIntegrationTest.java` (신규) | 준비 단계 판정 |
| `palim-app/src/test/.../integration/HomeScreenRenderIntegrationTest.java` (신규) | 홈이 두 상태로 그려진다 |
| `palim-app/src/test/.../integration/ConnectorDetailScreenRenderIntegrationTest.java` (신규) | 상세 화면 · 자동 수집 저장 |

---

### Task 1: 준비 단계가 실제 상태를 말하게 한다

지금 `SetupService` 는 품목 맞추기·재고 대조·자동 실행을 **이미 만들어 배포했는데도** 「준비 중」
이라고 답한다. 이 값을 홈으로 올리면 첫 화면이 매일 거짓말을 한다. **먼저 고친다.**

1단계도 틀렸다. 「연결됨」만 보는데, **칸을 맞추지 않으면 수집이 돌지 않는다**
(`ConnectorScheduler.shouldRun` 이 ACTIVE 매핑을 요구한다). 연결 2개만으로 완료를 주면 데이터가
안 들어오는데 화면은 다 됐다고 한다.

4단계는 `scheduleCron != null` 하나만 보는데 스케줄러의 실제 조건과 어긋난다.

**Files:**
- Modify: `palim-web/src/main/java/kr/suhsaechan/palim/web/setup/SetupService.java`
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/SetupStateIntegrationTest.java`

**Interfaces:**
- Consumes: `ConnectorRepository.findByTenantIdOrderByName(UUID)`,
  `ConnectorMappingRepository.findByConnectorIdAndStatus(UUID, MappingStatus)`,
  `ReconcileUnitService.pending()` → `List<ReconcileUnitMember>`,
  `ReconcileDefinitionRepository.findByIsActiveTrueOrderByCode()` → `List<ReconcileDefinition>`,
  `ReconcileRunRepository.findFirstByDefinitionIdAndStatusOrderByStartedAtDesc(UUID, RunStatus)`
- Produces: `SetupService.steps()` → `List<SetupStep>` (기존 시그니처 유지). 4단계 순서·제목은
  그대로, `state`·`detail`·`action`·`link` 만 실제 값이 된다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`palim-app/src/test/java/kr/suhsaechan/palim/integration/SetupStateIntegrationTest.java`

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.web.connector.ConnectorAdminService;
import kr.suhsaechan.palim.web.setup.SetupStep;
import kr.suhsaechan.palim.web.setup.SetupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 준비 상태판이 <b>사실</b>을 말하는가.
 *
 * <p>이 값이 홈의 첫 화면이 된다. 여기서 「준비 중」이라고 하면 사장님은 이미 있는 기능 앞에서
 * 멈춘다. 실제로 그렇게 굳어 있었다 — 품목 맞추기·대조·자동 실행을 만들어 배포한 뒤에도
 * 화면은 계속 준비 중이라고 답했다.
 */
class SetupStateIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = ConnectorAdminService.DEFAULT_TENANT;

    @Autowired private SetupService setupService;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private TargetModelRepository targetModelRepository;

    private Connector connector(String name) {
        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        return connectorRepository.save(Connector.of(
                TENANT, "setup-" + UUID.randomUUID().toString().substring(0, 8),
                name, model.getId(), SourceType.HTTP_API, "EA"));
    }

    private SetupStep stepOf(List<SetupStep> steps, int order) {
        return steps.stream().filter(s -> s.order() == order).findFirst().orElseThrow();
    }

    /**
     * 만들어 둔 화면을 「준비 중」이라고 말하면 안 된다. 사장님은 그 앞에서 멈추는데, 정작
     * 사이드바에는 같은 기능이 링크로 떠 있다.
     */
    @Test
    @DisplayName("이미 만든 단계를 준비 중이라고 하지 않는다")
    void 준비_중이라고_하지_않는다() {
        List<SetupStep> steps = setupService.steps();

        assertThat(steps)
                .as("만들어 둔 것을 준비 중이라고 하면 거기서 길이 끊긴다")
                .noneSatisfy(step ->
                        assertThat(step.state()).isEqualTo(SetupStep.State.NOT_READY));
    }

    /**
     * 갈 곳이 있어야 「하러 가기」가 눌린다. 링크가 없으면 사장님은 사이드바에서 직접 찾아야 한다.
     */
    @Test
    @DisplayName("아직 할 일이 남은 단계는 갈 곳을 알려준다")
    void 갈_곳을_알려준다() {
        List<SetupStep> steps = setupService.steps();

        assertThat(steps)
                .filteredOn(step -> step.state() == SetupStep.State.ATTENTION)
                .as("손봐야 한다고 말하면서 어디로 가야 할지 안 알려주면 소용이 없다")
                .allSatisfy(step -> assertThat(step.link()).isNotBlank());
    }

    /**
     * 연결만으로는 자료가 한 줄도 안 들어온다 — 수집은 확정된 칸 맞추기를 요구한다. 연결
     * 두 개만으로 완료라고 하면 며칠 뒤 «왜 데이터가 없지» 로 알게 된다.
     */
    @Test
    @DisplayName("칸을 맞추지 않았으면 연결 단계가 끝난 것이 아니다")
    void 칸을_맞춰야_끝난다() {
        connector("전산");
        connector("물류");

        SetupStep first = stepOf(setupService.steps(), 1);

        assertThat(first.state())
                .as("연결만 해서는 수집이 돌지 않는다")
                .isNotEqualTo(SetupStep.State.DONE);
        assertThat(first.detail()).contains("칸");
    }

    /**
     * 아무것도 없을 때는 「첫 시스템을 붙이세요」 하나만 보여야 한다. 여러 개를 나열하면 무엇부터
     * 할지 다시 고민하게 된다.
     */
    @Test
    @DisplayName("아무것도 없으면 첫 걸음만 짚어 준다")
    void 첫_걸음만_짚는다() {
        SetupStep first = stepOf(setupService.steps(), 1);

        assertThat(first.state()).isEqualTo(SetupStep.State.ATTENTION);
        assertThat(first.link()).isEqualTo("/connectors/connect");
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

```bash
JAVA_HOME=/Users/suhsaechan/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
./gradlew :palim-app:test --tests "*SetupStateIntegrationTest*" \
  -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

기대: `준비_중이라고_하지_않는다` 와 `칸을_맞춰야_끝난다` 가 FAIL. 2·3·4단계가 `NOT_READY` 이고
1단계가 매핑을 안 보기 때문이다.

- [ ] **Step 3: `SetupService` 를 실제 상태로 고친다**

`palim-web/src/main/java/kr/suhsaechan/palim/web/setup/SetupService.java` 전체를 아래로 바꾼다.

```java
package kr.suhsaechan.palim.web.setup;

import java.util.ArrayList;
import java.util.List;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.MappingStatus;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileRunRepository;
import kr.suhsaechan.palim.reconcile.run.RunStatus;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitService;
import kr.suhsaechan.palim.web.connector.ConnectorAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 준비 상태판.
 *
 * <p>처음 오는 사람의 진입점이다. 흩어진 화면을 순서대로 잇고, 지금 막힌 곳을 짚어 준다.
 *
 * <p><b>사실만 말한다.</b> 만들어 둔 화면을 「준비 중」이라고 하면 사장님은 거기서 멈추는데,
 * 정작 사이드바에는 같은 기능이 링크로 떠 있다. 한 번 그렇게 굳은 적이 있어 이 원칙을 적어 둔다.
 */
@Service
@RequiredArgsConstructor
public class SetupService {

    private final ConnectorRepository connectorRepository;
    private final ConnectorMappingRepository mappingRepository;
    private final ReconcileUnitService unitService;
    private final ReconcileDefinitionRepository definitionRepository;
    private final ReconcileRunRepository runRepository;

    @Transactional(readOnly = true)
    public List<SetupStep> steps() {
        List<Connector> connectors =
                connectorRepository.findByTenantIdOrderByName(ConnectorAdminService.DEFAULT_TENANT);
        List<Connector> collecting = connectors.stream().filter(this::collects).toList();

        List<SetupStep> steps = new ArrayList<>();
        steps.add(connectionStep(connectors, collecting));
        steps.add(matchingStep(collecting));
        steps.add(reconcileStep(collecting));
        steps.add(scheduleStep(collecting));
        return steps;
    }

    /**
     * 자료가 실제로 들어오는 연동인가.
     *
     * <p>연결과 칸 맞추기가 <b>둘 다</b> 끝나야 한 줄이라도 들어온다. 연결만 보고 완료라고 하면
     * 며칠 뒤 「왜 데이터가 없지」로 알게 된다.
     */
    private boolean collects(Connector connector) {
        return connector.getConnectionStatus().isUsable()
                && mappingRepository
                        .findByConnectorIdAndStatus(connector.getId(), MappingStatus.ACTIVE)
                        .isPresent();
    }

    private SetupStep connectionStep(List<Connector> all, List<Connector> collecting) {
        if (all.isEmpty()) {
            return new SetupStep(1, "재고 가져오는 곳", SetupStep.State.ATTENTION,
                    "아직 연결한 시스템이 없습니다",
                    "전산·물류 시스템을 연결하세요", "/connectors/connect");
        }

        // 붙여는 뒀는데 칸을 안 맞춘 것이 있으면 그것이 다음 걸음이다. 새로 붙이는 것보다
        // 이미 붙인 것을 끝내는 편이 자료가 빨리 들어온다.
        Connector unfinished = all.stream()
                .filter(c -> !collecting.contains(c))
                .findFirst().orElse(null);
        if (unfinished != null) {
            return new SetupStep(1, "재고 가져오는 곳", SetupStep.State.ATTENTION,
                    "%s — %s".formatted(unfinished.getName(), nextActionOf(unfinished)),
                    unfinished.getConnectionStatus().isUsable()
                            ? "칸을 맞춰야 자료가 들어옵니다"
                            : unfinished.getConnectionStatus().getNextAction(),
                    "/connectors/" + unfinished.getId());
        }
        if (collecting.size() < 2) {
            return new SetupStep(1, "재고 가져오는 곳", SetupStep.State.ATTENTION,
                    "자료가 들어오는 곳 %d 군데 — 맞춰 보려면 둘이 필요합니다"
                            .formatted(collecting.size()),
                    "나머지 시스템을 연결하세요", "/connectors/connect");
        }
        return new SetupStep(1, "재고 가져오는 곳", SetupStep.State.DONE,
                "%d 군데에서 자료가 들어옵니다".formatted(collecting.size()), null, "/connectors");
    }

    /** 칸을 맞추지 않은 것과 연결이 덜 된 것을 한 문장으로 구분해 말한다. */
    private String nextActionOf(Connector connector) {
        return connector.getConnectionStatus().isUsable()
                ? "칸 맞추기가 남았습니다"
                : connector.getConnectionStatus().getLabel();
    }

    private SetupStep matchingStep(List<Connector> collecting) {
        if (collecting.size() < 2) {
            return new SetupStep(2, "품목 맞추기", SetupStep.State.WAITING,
                    "자료가 들어오는 곳이 둘이 되면 시작합니다", null, null);
        }
        int pending = unitService.pending().size();
        if (pending > 0) {
            return new SetupStep(2, "품목 맞추기", SetupStep.State.ATTENTION,
                    "확인을 기다리는 품목 %d 건".formatted(pending),
                    "같은 물건인지 보고 확인해 주세요", "/reconcile/units");
        }
        return new SetupStep(2, "품목 맞추기", SetupStep.State.DONE,
                "확인을 기다리는 품목이 없습니다", null, "/reconcile/units");
    }

    private SetupStep reconcileStep(List<Connector> collecting) {
        List<ReconcileDefinition> definitions =
                definitionRepository.findByIsActiveTrueOrderByCode();
        if (definitions.isEmpty()) {
            return new SetupStep(3, "대조 결과", SetupStep.State.ATTENTION,
                    collecting.size() < 2
                            ? "자료가 들어오는 곳이 둘이 되면 맞춰 볼 수 있습니다"
                            : "무엇과 무엇을 맞춰 볼지 아직 정하지 않았습니다",
                    "맞춰 볼 대상을 정하세요", "/reconcile");
        }
        boolean ranOnce = definitions.stream().anyMatch(d ->
                runRepository.findFirstByDefinitionIdAndStatusOrderByStartedAtDesc(
                        d.getId(), RunStatus.SUCCESS).isPresent());
        return new SetupStep(3, "대조 결과",
                ranOnce ? SetupStep.State.DONE : SetupStep.State.WAITING,
                ranOnce ? "맞춰 본 기록이 있습니다" : "아직 한 번도 맞춰 보지 않았습니다",
                null, "/reconcile");
    }

    /**
     * 매일 스스로 도는가.
     *
     * <p>수집이 도는 조건은 <b>시각만이 아니다</b> — 확정된 칸 맞추기가 있어야 하고 파일 업로드
     * 방식은 애초에 자동으로 돌지 않는다. 시각만 보고 «예약되어 있습니다» 라고 하면, 스케줄러는
     * 영원히 건너뛰는데 화면만 다 됐다고 말한다.
     */
    private SetupStep scheduleStep(List<Connector> collecting) {
        if (collecting.isEmpty()) {
            return new SetupStep(4, "매일 자동으로", SetupStep.State.WAITING,
                    "자료가 들어오기 시작하면 시각을 정할 수 있습니다", null, null);
        }
        List<Connector> schedulable = collecting.stream()
                .filter(c -> c.getSourceType() != SourceType.UPLOAD)
                .toList();
        if (schedulable.isEmpty()) {
            return new SetupStep(4, "매일 자동으로", SetupStep.State.WAITING,
                    "파일로 올리는 방식은 자동으로 돌지 않습니다", null, null);
        }
        Connector notScheduled = schedulable.stream()
                .filter(c -> !StringUtils.hasText(c.getScheduleCron()))
                .findFirst().orElse(null);
        if (notScheduled != null) {
            return new SetupStep(4, "매일 자동으로", SetupStep.State.ATTENTION,
                    "%s 의 수집 시각이 정해지지 않았습니다".formatted(notScheduled.getName()),
                    "몇 시에 가져올지 정하세요", "/connectors/" + notScheduled.getId());
        }
        return new SetupStep(4, "매일 자동으로", SetupStep.State.DONE,
                "%d 군데가 매일 스스로 가져옵니다".formatted(schedulable.size()), null, "/connectors");
    }
}
```

`hasAttention()` 은 호출자가 한 곳도 없는 죽은 메서드이므로 위 교체에서 함께 사라진다.

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

```bash
JAVA_HOME=/Users/suhsaechan/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
./gradlew :palim-app:test --tests "*SetupStateIntegrationTest*" \
  -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

기대: 4건 PASS.

- [ ] **Step 5: 기존 테스트가 안 깨졌는지 확인한다**

```bash
JAVA_HOME=/Users/suhsaechan/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
./gradlew build \
  -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

기대: BUILD SUCCESSFUL. `/setup` 화면은 `AllScreensRenderIntegrationTest` 가 여는데, 단계 제목과
문구만 바뀌므로 통과해야 한다.

- [ ] **Step 6: 커밋**

```bash
git status --short
git add palim-web/src/main/java/kr/suhsaechan/palim/web/setup/SetupService.java \
        palim-app/src/test/java/kr/suhsaechan/palim/integration/SetupStateIntegrationTest.java
git commit -m "연동 준비 상태판 — 처음 쓰는 사람이 따라가면 완성되는 흐름 : fix : 준비 단계가 이미 만든 화면을 준비 중이라고 하던 문제 수정 https://github.com/Cassiiopeia/palim/issues/68"
```

---

### Task 2: 홈이 준비 상태와 오늘 결과를 흡수한다

「시작하기」가 메뉴의 한 항목이면 그것은 여러 기능 중 하나로 보인다. 순서를 안내하는 화면이
순서 밖에 있으면 안 된다. **홈이 그 일을 한다** — 준비가 안 끝났으면 다음 한 걸음, 끝났으면
오늘 결과.

**Files:**
- Create: `palim-web/src/main/java/kr/suhsaechan/palim/web/home/TodayReconcile.java`
- Create: `palim-web/src/main/java/kr/suhsaechan/palim/web/home/HomeSummaryService.java`
- Modify: `palim-web/src/main/java/kr/suhsaechan/palim/web/HomeController.java`
- Modify: `palim-web/src/main/java/kr/suhsaechan/palim/web/setup/SetupController.java`
- Create: `palim-web/src/main/resources/templates/fragments/setup-steps.html`
- Modify: `palim-web/src/main/resources/templates/home.html`
- Modify: `palim-app/src/test/java/kr/suhsaechan/palim/integration/AllScreensRenderIntegrationTest.java`
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/HomeScreenRenderIntegrationTest.java`

**Interfaces:**
- Consumes: `SetupService.steps()` (Task 1)
- Produces: `HomeSummaryService.today()` → `TodayReconcile`.
  `TodayReconcile(boolean ran, Instant at, int confirmed, int observing, int unmatched)` —
  `ran` 이 false 면 나머지는 0 이다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`palim-app/src/test/java/kr/suhsaechan/palim/integration/HomeScreenRenderIntegrationTest.java`

```java
package kr.suhsaechan.palim.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 홈이 <b>지금 할 일</b>을 말하는가.
 *
 * <p>「시작하기」를 메뉴의 한 항목으로 두면 여러 기능 중 하나로 보인다. 순서를 안내하는 화면이
 * 순서 밖에 있으면 안 되므로 홈이 그 일을 한다.
 */
@AutoConfigureMockMvc
class HomeScreenRenderIntegrationTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;

    /**
     * 아직 아무것도 붙이지 않은 상태 — 처음 오는 사람이 보는 화면이다. 여기서 다음 한 걸음이
     * 안 보이면 사장님은 사이드바를 뒤져야 한다.
     */
    @Test
    @WithMockUser
    @DisplayName("준비가 안 끝났으면 다음 한 걸음을 짚어 준다")
    void 다음_걸음을_짚는다() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("재고 가져오는 곳")))
                .andExpect(content().string(containsString("하러 가기")))
                .andExpect(RenderAssertions.fullyRendered());
    }

    /** 옛 주소를 눌러도 같은 곳에 닿아야 한다. 북마크가 죽으면 「없어졌나」로 읽힌다. */
    @Test
    @WithMockUser
    @DisplayName("옛 시작하기 주소는 홈으로 보낸다")
    void 옛_주소는_홈으로() throws Exception {
        mockMvc.perform(get("/setup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/"));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

```bash
JAVA_HOME=/Users/suhsaechan/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
./gradlew :palim-app:test --tests "*HomeScreenRenderIntegrationTest*" \
  -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

기대: 둘 다 FAIL. 홈은 텔레그램 안내를 그리고 있고, `/setup` 은 200 을 준다.

- [ ] **Step 3: 오늘 결과 요약을 만든다**

`palim-web/src/main/java/kr/suhsaechan/palim/web/home/TodayReconcile.java`

```java
package kr.suhsaechan.palim.web.home;

import java.time.Instant;

/**
 * 홈에 띄우는 오늘 한 줄.
 *
 * <p><b>「지금 볼 것」과 「지켜볼 것」과 「아직 안 이어진 품목」을 따로 센다.</b> 셋은 할 일이
 * 다르다 — 앞의 둘은 재고를 맞추는 일이고 마지막은 품목을 잇는 일이다. 한 숫자로 합치면 사장님이
 * 재고를 뒤지다가 정작 할 일이 품목 잇기였다는 것을 나중에 안다.
 */
public record TodayReconcile(boolean ran, Instant at, int confirmed, int observing, int unmatched) {

    public static TodayReconcile none() {
        return new TodayReconcile(false, null, 0, 0, 0);
    }

    /** 지금 손댈 것이 있는가. */
    public boolean needsAttention() {
        return confirmed > 0;
    }
}
```

`palim-web/src/main/java/kr/suhsaechan/palim/web/home/HomeSummaryService.java`

```java
package kr.suhsaechan.palim.web.home;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.run.DiffState;
import kr.suhsaechan.palim.reconcile.run.DiffType;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiffRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
import kr.suhsaechan.palim.reconcile.run.ReconcileRunRepository;
import kr.suhsaechan.palim.reconcile.run.RunStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 홈의 「오늘 맞춰 봤습니다」.
 *
 * <p>준비 상태 계산({@link kr.suhsaechan.palim.web.setup.SetupService})과 나눈 이유는 둘의
 * 수명이 다르기 때문이다. 준비 상태는 처음 며칠만 쓰이고, 이 요약은 그 뒤로 매일 쓰인다.
 */
@Service
@RequiredArgsConstructor
public class HomeSummaryService {

    private final ReconcileDefinitionRepository definitions;
    private final ReconcileRunRepository runs;
    private final ReconcileDiffRepository diffs;

    @Transactional(readOnly = true)
    public TodayReconcile today() {
        Optional<ReconcileRun> latest = definitions.findByIsActiveTrueOrderByCode().stream()
                .map(this::latestSuccess)
                .flatMap(Optional::stream)
                .max(Comparator.comparing(ReconcileRun::getStartedAt));

        if (latest.isEmpty()) {
            return TodayReconcile.none();
        }

        ReconcileRun run = latest.get();
        List<ReconcileDiff> found = diffs.findByRunIdOrderByStateAscUnitCodeAsc(run.getId());

        // 미매칭도 관찰중으로 저장된다. 상태만 보고 세면 «재고를 맞출 일» 과 «품목을 이을 일»
        // 이 한 숫자에 섞여, 사장님이 엉뚱한 화면을 뒤지게 된다.
        int unmatched = (int) found.stream().filter(d -> isUnmatched(d.getDiffType())).count();
        int confirmed = (int) found.stream()
                .filter(d -> !isUnmatched(d.getDiffType()))
                .filter(d -> d.getState() == DiffState.CONFIRMED).count();
        int observing = (int) found.stream()
                .filter(d -> !isUnmatched(d.getDiffType()))
                .filter(d -> d.getState() == DiffState.OBSERVING).count();

        return new TodayReconcile(true, run.getStartedAt(), confirmed, observing, unmatched);
    }

    private boolean isUnmatched(DiffType type) {
        return type == DiffType.UNMATCHED_LEFT || type == DiffType.UNMATCHED_RIGHT;
    }

    private Optional<ReconcileRun> latestSuccess(ReconcileDefinition definition) {
        return runs.findFirstByDefinitionIdAndStatusOrderByStartedAtDesc(
                definition.getId(), RunStatus.SUCCESS);
    }
}
```

- [ ] **Step 4: 홈 컨트롤러가 둘을 넘긴다**

`palim-web/src/main/java/kr/suhsaechan/palim/web/HomeController.java` 의 `home` 메서드를 바꾸고
필드를 추가한다.

```java
package kr.suhsaechan.palim.web;

import kr.suhsaechan.palim.web.home.HomeSummaryService;
import kr.suhsaechan.palim.web.setup.SetupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 진입 화면과 로그인 화면.
 *
 * <p>홈은 <b>같은 자리가 시간에 따라 다른 것을 보여준다.</b> 준비가 안 끝났으면 다음 한 걸음을,
 * 끝났으면 오늘 맞춰 본 결과를 띄운다. 순서를 안내하는 화면을 메뉴의 한 항목으로 두면 여러 기능
 * 중 하나로 보이기 때문에 여기가 그 일을 한다.
 */
@Controller
@RequiredArgsConstructor
public class HomeController {

    private final SetupService setupService;
    private final HomeSummaryService homeSummaryService;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("title", "홈");
        model.addAttribute("steps", setupService.steps());
        model.addAttribute("today", homeSummaryService.today());
        return "home";
    }

    /**
     * 로그인 화면.
     *
     * <p>Spring Security 기본 페이지를 쓰지 않는 이유는 콘텐츠 보안 정책 때문이다. 기본 페이지는
     * 인라인 스타일을 쓰는데, {@code style-src 'self'} 로 제한하면 스타일이 적용되지 않는다.
     */
    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
```

- [ ] **Step 5: 옛 주소를 홈으로 넘긴다**

`palim-web/src/main/java/kr/suhsaechan/palim/web/setup/SetupController.java` 전체를 바꾼다.

```java
package kr.suhsaechan.palim.web.setup;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 옛 준비 상태판 주소.
 *
 * <p>이 화면은 홈으로 합쳐졌다. 주소를 없애지 않고 넘기는 이유는 북마크한 사람이 있을 수
 * 있어서다 — 404 를 만나면 「없어졌나」로 읽힌다.
 */
@Controller
public class SetupController {

    @GetMapping("/setup")
    public String index() {
        return "redirect:/";
    }
}
```

`palim-web/src/main/resources/templates/setup/index.html` 은 **지우지 않는다.** `/setup` 이
홈으로 넘어가므로 더 이상 그려지지 않지만, 파일 삭제는 별도 판단이라 이 계획에서는 손대지 않는다.

- [ ] **Step 6: 단계 카드를 조각으로 뽑는다**

홈과 옛 상태판이 같은 카드를 각자 그리면 문구를 한쪽만 고치는 일이 생긴다. **한 곳에 두고
불러 쓴다.**

`palim-web/src/main/resources/templates/fragments/setup-steps.html` (신규)

```html
<!DOCTYPE html>
<!--/* 준비 단계 카드.

       홈이 이것을 불러 쓴다. 카드를 화면마다 따로 그리면 문구를 한쪽만 고치는 일이 생긴다. */-->
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<body>

<div th:fragment="steps(steps)" class="flex flex-col gap-3">
    <div th:each="step : ${steps}"
         th:classappend="${step.state.name() == 'ATTENTION'} ? 'border-warning' :
                         (${step.done} ? 'border-success/40' : 'border-base-300')"
         class="card bg-base-100 border shadow-sm">
        <div class="card-body flex-row items-center gap-4 py-4">

            <div class="text-2xl w-8 text-center">
                <span th:if="${step.done}" class="text-success">✅</span>
                <span th:if="${step.state.name() == 'ATTENTION'}" class="text-warning">⚠️</span>
                <span th:if="${step.state.name() == 'WAITING'}" class="opacity-30">⬜</span>
            </div>

            <div class="grow">
                <div class="font-semibold" th:text="${step.order} + '. ' + ${step.title}">단계</div>
                <div class="text-sm text-base-content/70" th:text="${step.detail}">상황</div>
                <div th:if="${step.action != null and step.state.name() == 'ATTENTION'}"
                     class="text-sm text-warning mt-1" th:text="'→ ' + ${step.action}">할 일</div>
            </div>

            <div>
                <a th:if="${step.actionable}" th:href="@{${step.link}}"
                   class="btn btn-warning btn-sm">하러 가기</a>
                <a th:if="${step.done and step.link != null}" th:href="@{${step.link}}"
                   class="btn btn-ghost btn-sm">확인</a>
            </div>
        </div>
    </div>
</div>

</body>
</html>
```

- [ ] **Step 7: 홈 화면을 다시 그린다**

`palim-web/src/main/resources/templates/home.html` 전체를 바꾼다.

```html
<!DOCTYPE html>
<!--/* 홈.

       같은 자리가 시간에 따라 다른 것을 보여준다. 준비가 안 끝났으면 «다음 한 걸음» 하나만,
       끝났으면 «오늘 결과». 여러 개를 나열하면 무엇부터 할지 다시 고민하게 된다. */-->
<html lang="ko" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: layout(${title}, ~{::content})}">
<body>
<div th:fragment="content" th:remove="tag">

    <!--/* 맞춰 본 적이 있으면 그것이 먼저다. 매일 여는 화면이 되어야 하기 때문이다. */-->
    <div th:if="${today.ran}" class="card bg-base-100 shadow-sm mb-6">
        <div class="card-body">
            <div class="flex items-center justify-between flex-wrap gap-2">
                <h2 class="card-title text-base">오늘 맞춰 봤습니다</h2>
                <span class="text-sm text-base-content/60"
                      th:text="${#temporals.format(today.at, 'MM-dd HH:mm')}">06:30</span>
            </div>

            <div class="flex flex-wrap gap-6 mt-2">
                <div>
                    <div class="text-sm text-base-content/70">지금 볼 것</div>
                    <div class="text-2xl font-semibold"
                         th:classappend="${today.needsAttention} ? 'text-error' : ''"
                         th:text="${today.confirmed} + '건'">0건</div>
                </div>
                <div>
                    <div class="text-sm text-base-content/70">지켜볼 것</div>
                    <div class="text-2xl font-semibold" th:text="${today.observing} + '건'">0건</div>
                </div>
                <!--/* 미매칭은 «재고를 맞출 일» 이 아니라 «품목을 이을 일» 이라 따로 센다. */-->
                <div th:if="${today.unmatched > 0}">
                    <div class="text-sm text-base-content/70">아직 안 이어진 품목</div>
                    <div class="text-2xl font-semibold" th:text="${today.unmatched} + '건'">0건</div>
                </div>
            </div>

            <div class="card-actions justify-end">
                <a th:href="@{/reconcile}" class="btn btn-sm">대조 결과 보기</a>
                <a th:if="${today.unmatched > 0}" th:href="@{/reconcile/units}"
                   class="btn btn-sm btn-ghost">품목 맞추러 가기</a>
            </div>
        </div>
    </div>

    <div th:unless="${today.ran}" class="prose max-w-none mb-6">
        <p class="text-base-content/70">
            전산 시스템과 물류 시스템의 재고를 매일 자동으로 맞춰 봅니다.
            아래 순서대로 준비하면 됩니다. <b>지금 손봐야 할 곳은 색으로 표시</b>됩니다.
        </p>
    </div>

    <div th:replace="~{fragments/setup-steps :: steps(${steps})}"></div>

</div>
</body>
</html>
```

- [ ] **Step 8: 전 화면 테스트에서 옛 주소를 뺀다**

`palim-app/src/test/java/kr/suhsaechan/palim/integration/AllScreensRenderIntegrationTest.java`
의 `@ValueSource` 에서 `"/setup"` 한 줄을 지운다. 이제 그 주소는 200 이 아니라 302 이므로 이
목록에 있으면 실패한다. 홈(`"/"`)은 이미 목록에 있다.

- [ ] **Step 9: 테스트가 통과하는 것을 확인한다**

```bash
JAVA_HOME=/Users/suhsaechan/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
./gradlew build \
  -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

기대: BUILD SUCCESSFUL.

- [ ] **Step 10: 커밋**

```bash
git status --short
git add palim-web/src/main/java/kr/suhsaechan/palim/web/home/ \
        palim-web/src/main/resources/templates/fragments/setup-steps.html \
        palim-web/src/main/java/kr/suhsaechan/palim/web/HomeController.java \
        palim-web/src/main/java/kr/suhsaechan/palim/web/setup/SetupController.java \
        palim-web/src/main/resources/templates/home.html \
        palim-app/src/test/java/kr/suhsaechan/palim/integration/HomeScreenRenderIntegrationTest.java \
        palim-app/src/test/java/kr/suhsaechan/palim/integration/AllScreensRenderIntegrationTest.java
git commit -m "연동 준비 상태판 — 처음 쓰는 사람이 따라가면 완성되는 흐름 : feat : 홈이 다음 한 걸음과 오늘 결과를 보여준다 https://github.com/Cassiiopeia/palim/issues/68"
```

---

### Task 3: 목록이 연결과 칸 맞추기를 나란히 보여준다

연결만 하면 끝난 것처럼 보이던 문제가 여기서 사라진다. 목록 한 줄에 「연결」과 「칸 맞추기」가
같이 있으면 ⚠ 가 곧 할 일이 된다.

**Files:**
- Modify: `palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorSummary.java`
- Modify: `palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorQueryService.java`
- Modify: `palim-web/src/main/resources/templates/connector/list.html`
- Modify: `palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorController.java` (목록 제목)
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/RunHistoryScreenRenderIntegrationTest.java` (기존 파일에 추가)

**Interfaces:**
- Consumes: 없음
- Produces: `ConnectorSummary` 에 `connectionStatus`(String)·`scheduleCron`(String) 두 칸이 늘어난다.
  `ConnectorSummary.connected()`·`scheduled()` 로 화면이 판단한다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`RunHistoryScreenRenderIntegrationTest.java` 의 `커넥터_목록이_그려진다` 를 아래로 바꾼다.

```java
    @Test
    @WithMockUser
    @DisplayName("목록이 연결과 칸 맞추기를 나란히 보여준다")
    void 커넥터_목록이_그려진다() throws Exception {
        mockMvc.perform(get("/connectors"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("실행 이력 확인용")))
                // 연결만 하면 끝난 것처럼 보이던 문제를 이 두 칸이 막는다
                .andExpect(content().string(containsString("연결")))
                .andExpect(content().string(containsString("칸 맞추기")))
                .andExpect(RenderAssertions.fullyRendered());
    }
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

```bash
JAVA_HOME=/Users/suhsaechan/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
./gradlew :palim-app:test --tests "*RunHistoryScreenRenderIntegrationTest*" \
  -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

기대: `커넥터_목록이_그려진다` 가 FAIL — 「칸 맞추기」라는 머리글이 없다.

- [ ] **Step 3: 요약에 두 칸을 추가한다**

`palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorSummary.java`

```java
package kr.suhsaechan.palim.web.connector;

import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.connector.define.ConnectionStatus;
import org.springframework.util.StringUtils;

/**
 * 목록 한 줄.
 *
 * <p><b>「연결」과 「칸 맞추기」를 나란히 담는다.</b> 연결만 하면 자료가 한 줄도 안 들어오는데,
 * 둘 중 하나만 보이면 끝난 것으로 착각한다.
 */
public record ConnectorSummary(UUID id, String code, String name, String targetModelName,
                               String sourceType, boolean enabled, Integer activeVersion,
                               String lastStatus, Instant lastRunAt, int lastSuccess,
                               int lastFailed, String connectionStatus, String scheduleCron) {

    public boolean readyForLive() {
        return activeVersion != null;
    }

    public boolean hasFailure() {
        return lastFailed > 0 || "FAILED".equals(lastStatus);
    }

    /** 연결이 끝났는가. 화면이 enum 이름을 알 필요가 없도록 여기서 판단한다. */
    public boolean connected() {
        return ConnectionStatus.VERIFIED_LIVE.name().equals(connectionStatus);
    }

    public String connectionLabel() {
        if (!StringUtils.hasText(connectionStatus)) {
            return ConnectionStatus.NOT_CONFIGURED.getLabel();
        }
        return ConnectionStatus.valueOf(connectionStatus).getLabel();
    }

    public boolean scheduled() {
        return StringUtils.hasText(scheduleCron);
    }
}
```

- [ ] **Step 4: 조회에 두 칸을 더한다**

`ConnectorQueryService.list` 의 SQL `SELECT` 절에 두 줄을 더한다. `FROM` 이하는 그대로다.

```java
        return jdbcClient.sql("""
                        SELECT c.id, c.code, c.name, m.name AS target_model_name,
                               c.source_type, c.enabled,
                               am.version AS active_version,
                               r.status AS last_status, r.started_at AS last_run_at,
                               coalesce(r.success_count, 0)::int AS last_success,
                               coalesce(r.failed_count, 0)::int AS last_failed,
                               c.connection_status, c.schedule_cron
                        FROM connector c
                        JOIN target_model m ON m.id = c.target_model_id
                        LEFT JOIN connector_mapping am
                               ON am.connector_id = c.id AND am.status = 'ACTIVE'
                        LEFT JOIN LATERAL (
                            SELECT status, started_at, success_count, failed_count
                            FROM connector_run
                            WHERE connector_id = c.id
                            ORDER BY started_at DESC
                            LIMIT 1
                        ) r ON true
                        WHERE c.tenant_id = :tenantId
                        ORDER BY c.name
                        """)
```

- [ ] **Step 5: 목록 화면에 두 칸을 그린다**

`palim-web/src/main/resources/templates/connector/list.html` 에서 표 머리글과 본문을 바꾼다.
설명 문구와 버튼도 함께 고친다.

머리글(`<thead>` 안):

```html
                    <tr>
                        <th>시스템</th>
                        <th>연결</th>
                        <th>칸 맞추기</th>
                        <th>자동 수집</th>
                        <th>마지막</th>
                        <th></th>
                    </tr>
```

본문 행(`<tbody>` 안의 `th:each` 행 전체를 아래로 교체):

```html
                    <tr th:each="c : ${connectors}">
                        <td>
                            <a th:href="@{/connectors/{id}(id=${c.id})}"
                               class="link link-hover font-medium" th:text="${c.name}">이름</a>
                            <div class="text-xs text-base-content/60" th:text="${c.sourceType}">원천</div>
                        </td>
                        <td>
                            <span th:if="${c.connected}" class="badge badge-success badge-sm">연결됨</span>
                            <span th:unless="${c.connected}" class="badge badge-warning badge-sm"
                                  th:text="${c.connectionLabel}">설정 필요</span>
                        </td>
                        <td>
                            <span th:if="${c.readyForLive}" class="badge badge-success badge-sm">맞춤</span>
                            <span th:unless="${c.readyForLive}" class="badge badge-warning badge-sm">안 함</span>
                        </td>
                        <td class="text-sm">
                            <span th:if="${c.scheduled}">매일</span>
                            <span th:unless="${c.scheduled}" class="text-base-content/50">—</span>
                        </td>
                        <td class="text-sm">
                            <span th:if="${c.lastRunAt != null}"
                                  th:text="${#temporals.format(c.lastRunAt, 'MM-dd HH:mm')}">—</span>
                            <span th:if="${c.lastRunAt == null}" class="text-base-content/50">실행 없음</span>
                        </td>
                        <td class="text-right">
                            <a th:href="@{/connectors/{id}(id=${c.id})}" class="btn btn-ghost btn-xs">보기</a>
                        </td>
                    </tr>
```

상단 설명과 버튼:

```html
    <div class="flex justify-between items-center mb-4">
        <p class="text-sm text-base-content/70">
            재고를 가져오는 시스템입니다. 새 시스템이 붙어도 배포 없이 여기서 추가합니다.
        </p>
        <div class="flex gap-2">
            <a th:href="@{/connectors/connect}" class="btn btn-primary btn-sm">+ 시스템 붙이기</a>
            <a th:href="@{/connectors/new}" class="btn btn-ghost btn-sm">엑셀·CSV 로 만들기</a>
        </div>
    </div>
```

빈 상태 카드의 버튼도 바꾼다.

```html
    <div th:if="${connectors.isEmpty()}" class="card bg-base-100 shadow-sm">
        <div class="card-body items-center text-center py-12">
            <h2 class="card-title">아직 연결한 시스템이 없습니다</h2>
            <p class="text-base-content/70">
                전산 시스템과 물류 시스템을 붙이면 매일 재고를 가져와 맞춰 봅니다.
            </p>
            <a th:href="@{/connectors/connect}" class="btn btn-primary mt-2">첫 시스템 붙이기</a>
        </div>
    </div>
```

`ConnectorController` 의 목록 제목도 바꾼다(59~62줄 부근).

```java
        model.addAttribute("title", "재고 가져오는 곳");
```

- [ ] **Step 6: 테스트가 통과하는 것을 확인한다**

```bash
JAVA_HOME=/Users/suhsaechan/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
./gradlew build \
  -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

기대: BUILD SUCCESSFUL. 이 시점에 목록의 이름·「보기」는 아직 없는 `/connectors/{id}` 를
가리키므로 **누르면 404 다.** 다음 Task 에서 만든다.

- [ ] **Step 7: 커밋**

```bash
git status --short
git add palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorSummary.java \
        palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorQueryService.java \
        palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorController.java \
        palim-web/src/main/resources/templates/connector/list.html \
        palim-app/src/test/java/kr/suhsaechan/palim/integration/RunHistoryScreenRenderIntegrationTest.java
git commit -m "연동 준비 상태판 — 처음 쓰는 사람이 따라가면 완성되는 흐름 : feat : 목록이 연결과 칸 맞추기를 나란히 보여준다 https://github.com/Cassiiopeia/palim/issues/68"
```

---

### Task 4: 시스템별 상세 — 한 시스템의 모든 것을 한 자리에

「이카운트 연결만 확인하고 싶다」가 갈 곳이 지금 없다. 매핑·이력이 따로 흩어져 있어서
비밀번호만 바꾸고 싶어도 긴 흐름을 다시 타야 한다.

이 화면이 **자동 수집 시각과 단위 환산이 갈 자리**이기도 하다. 지금 자동 수집 시각을 넣는 UI 가
어디에도 없어서 스케줄러가 영원히 건너뛴다.

**Files:**
- Create: `palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorDetailView.java`
- Create: `palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorDetailController.java`
- Create: `palim-web/src/main/resources/templates/connector/detail.html`
- Test: `palim-app/src/test/java/kr/suhsaechan/palim/integration/ConnectorDetailScreenRenderIntegrationTest.java`

**Interfaces:**
- Consumes: `ConnectorAdminService.connector(UUID)` → `Connector`,
  `ConnectorQueryService.runs(UUID, int)` → `List<RunSummary>`,
  `ConnectorMappingRepository.findByConnectorIdAndStatus(UUID, MappingStatus)`,
  `Connector.schedule(String cron)`
- Produces: `GET /connectors/{id}` (템플릿 `connector/detail`),
  `POST /connectors/{id}/schedule` (파라미터 `hour`, `minute`; 없으면 예약 해제)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`palim-app/src/test/java/kr/suhsaechan/palim/integration/ConnectorDetailScreenRenderIntegrationTest.java`

```java
package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.web.connector.ConnectorAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 한 시스템의 상태가 <b>한 자리에</b> 모이는가.
 *
 * <p>지금은 연결·칸 맞추기·이력이 흩어져 있어서 「이 시스템만 확인하고 싶다」가 갈 곳이 없다.
 * 비밀번호만 바꾸고 싶은데 긴 흐름을 다시 타야 한다.
 */
@AutoConfigureMockMvc
class ConnectorDetailScreenRenderIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = ConnectorAdminService.DEFAULT_TENANT;

    @Autowired private MockMvc mockMvc;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private TargetModelRepository targetModelRepository;

    private Connector connector;

    @BeforeEach
    void setUp() {
        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        connector = connectorRepository.save(Connector.of(
                TENANT, "detail-" + UUID.randomUUID().toString().substring(0, 8),
                "상세 확인용", model.getId(), SourceType.HTTP_API, "EA"));
    }

    @Test
    @WithMockUser
    @DisplayName("한 시스템의 상태가 한 자리에 모인다")
    void 상세가_그려진다() throws Exception {
        mockMvc.perform(get("/connectors/{id}", connector.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("상세 확인용")))
                .andExpect(content().string(containsString("연결")))
                .andExpect(content().string(containsString("칸 맞추기")))
                .andExpect(content().string(containsString("자동 수집")))
                // 적재가 단위 때문에 막혔을 때 풀 화면으로 갈 수 있어야 한다
                .andExpect(content().string(containsString("단위 환산")))
                .andExpect(RenderAssertions.fullyRendered());
    }

    /**
     * 자동 수집 시각을 넣을 자리가 지금 어디에도 없어 스케줄러가 영원히 건너뛴다. 사장님은
     * cron 을 모르므로 <b>시각만</b> 고르게 하고 표현식은 화면이 만든다.
     */
    @Test
    @WithMockUser
    @DisplayName("몇 시에 가져올지 정할 수 있다")
    void 수집_시각을_정한다() throws Exception {
        mockMvc.perform(post("/connectors/{id}/schedule", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("hour", "6")
                        .param("minute", "30"))
                .andExpect(status().is3xxRedirection());

        Connector saved = connectorRepository.findById(connector.getId()).orElseThrow();
        assertThat(saved.getScheduleCron())
                .as("스케줄러가 읽는 것은 cron 이다 — 화면이 시각을 표현식으로 옮겨야 한다")
                .isEqualTo("0 30 6 * * *");
    }

    /** 자동으로 안 가져오게 되돌릴 수도 있어야 한다. 켜기만 되고 끄기가 없으면 갇힌다. */
    @Test
    @WithMockUser
    @DisplayName("자동 수집을 끌 수 있다")
    void 수집을_끈다() throws Exception {
        mockMvc.perform(post("/connectors/{id}/schedule", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("hour", "")
                        .param("minute", ""))
                .andExpect(status().is3xxRedirection());

        assertThat(connectorRepository.findById(connector.getId()).orElseThrow()
                .getScheduleCron()).isNull();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

```bash
JAVA_HOME=/Users/suhsaechan/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
./gradlew :palim-app:test --tests "*ConnectorDetailScreenRenderIntegrationTest*" \
  -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

기대: 3건 모두 FAIL (404).

- [ ] **Step 3: 화면 한 장분을 담는 record 를 만든다**

`palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorDetailView.java`

```java
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
```

- [ ] **Step 4: 컨트롤러를 만든다**

`palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorDetailController.java`

```java
package kr.suhsaechan.palim.web.connector;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.MappingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 시스템 하나를 손보는 화면.
 *
 * <p>목록이 진입점이고 여기가 그 아래다. 처음 붙일 때는 「연결 → 칸 맞추기」를 한 호흡으로
 * 가지만, 이미 붙여 둔 것을 손볼 때 그 긴 흐름을 다시 타게 하면 안 된다 — 비밀번호만 바꾸고
 * 싶은 사람에게 칸 맞추기를 다시 시킬 이유가 없다.
 */
@Controller
@RequiredArgsConstructor
public class ConnectorDetailController {

    private final ConnectorRepository connectorRepository;
    private final ConnectorAdminService adminService;
    private final ConnectorQueryService queryService;
    private final ConnectorMappingRepository mappingRepository;

    private static final int RECENT_RUN_LIMIT = 1;

    @GetMapping("/connectors/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        Connector connector = adminService.connector(id);
        Optional<ConnectorMapping> active =
                mappingRepository.findByConnectorIdAndStatus(id, MappingStatus.ACTIVE);
        List<RunSummary> runs = queryService.runs(id, RECENT_RUN_LIMIT);

        model.addAttribute("title", connector.getName());
        model.addAttribute("view", ConnectorDetailView.of(
                connector,
                active.isPresent(),
                active.map(ConnectorMapping::getVersion).orElse(null),
                runs.isEmpty() ? null : runs.get(0)));
        return "connector/detail";
    }

    /**
     * 매일 몇 시에 가져올지 정한다.
     *
     * <p>사장님은 cron 을 모른다. <b>시각만 고르게 하고 표현식은 여기서 만든다.</b> 비우면
     * 자동 수집을 끈다 — 켜기만 되고 끄기가 없으면 한 번 정한 뒤로 갇힌다.
     */
    @PostMapping("/connectors/{id}/schedule")
    public String schedule(@PathVariable UUID id,
                           @RequestParam(required = false) String hour,
                           @RequestParam(required = false) String minute,
                           RedirectAttributes redirectAttributes) {
        Connector connector = adminService.connector(id);

        if (hour == null || hour.isBlank()) {
            connector.schedule(null);
            connectorRepository.save(connector);
            redirectAttributes.addFlashAttribute("flashSuccess", "자동으로 가져오지 않습니다.");
            return "redirect:/connectors/" + id;
        }

        int h = Integer.parseInt(hour.trim());
        int m = (minute == null || minute.isBlank()) ? 0 : Integer.parseInt(minute.trim());
        connector.schedule("0 %d %d * * *".formatted(m, h));
        connectorRepository.save(connector);

        redirectAttributes.addFlashAttribute("flashSuccess",
                "매일 %02d:%02d 에 가져옵니다.".formatted(h, m));
        return "redirect:/connectors/" + id;
    }
}
```

`RunSummary` 는 `palim-web/.../connector/RunSummary.java` 에 있고 컴포넌트는
`(id, runMode, triggerType, status, mappingVersion, totalCount, successCount, failedCount,
startedAt, finishedAt, errorSummary)` 다 — 위 코드가 쓰는 이름과 일치한다.

`ConnectorMapping.getVersion()` 은 `int` 를 돌려준다. `Optional.map` 으로 감싸면 `Integer` 로
박싱되므로 `ConnectorDetailView` 의 `activeVersion` 은 `Integer` 가 맞다.

- [ ] **Step 5: 상세 화면을 만든다**

`palim-web/src/main/resources/templates/connector/detail.html`

```html
<!DOCTYPE html>
<!--/* 시스템 하나를 손보는 화면.

       한 자리에 모으는 이유는 «이 시스템만 확인하고 싶다» 가 갈 곳이 없어서다. 지금은 연결·칸
       맞추기·이력이 흩어져 있어 비밀번호만 바꾸려 해도 긴 흐름을 다시 타야 한다. */-->
<html lang="ko" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: layout(${title}, ~{::content})}">
<body>
<div th:fragment="content" th:remove="tag">

    <div class="flex items-center justify-between flex-wrap gap-2 mb-4">
        <div>
            <a th:href="@{/connectors}" class="text-sm link link-hover">← 재고 가져오는 곳</a>
            <h1 class="text-2xl font-bold tracking-tight" th:text="${view.name}">시스템</h1>
        </div>
        <span class="badge badge-ghost" th:text="${view.sourceType}">원천</span>
    </div>

    <div class="card bg-base-100 shadow-sm">
        <div class="card-body gap-0 p-0">

            <div class="flex items-center gap-4 p-4 border-b border-base-300">
                <div class="w-28 font-medium">연결</div>
                <div class="grow">
                    <span th:if="${view.connected}" class="badge badge-success badge-sm">연결됨</span>
                    <span th:unless="${view.connected}" class="badge badge-warning badge-sm"
                          th:text="${view.connectionStatus.label}">설정 필요</span>
                    <span th:if="${view.connectionStatus.nextAction != null}"
                          class="text-sm text-base-content/70 ml-2"
                          th:text="${view.connectionStatus.nextAction}">할 일</span>
                </div>
                <a th:href="@{/connectors/connect}" class="btn btn-ghost btn-sm">고치기</a>
            </div>

            <div class="flex items-center gap-4 p-4 border-b border-base-300">
                <div class="w-28 font-medium">칸 맞추기</div>
                <div class="grow">
                    <span th:if="${view.mappingActive}" class="badge badge-success badge-sm">맞춤</span>
                    <span th:unless="${view.mappingActive}" class="badge badge-warning badge-sm">안 함</span>
                    <span th:unless="${view.mappingActive}" class="text-sm text-base-content/70 ml-2">
                        칸을 맞춰야 자료가 들어옵니다
                    </span>
                </div>
                <a th:href="@{/connectors/{id}/mapping(id=${view.id})}"
                   class="btn btn-ghost btn-sm">
                    <span th:if="${view.mappingActive}">다시 맞추기</span>
                    <span th:unless="${view.mappingActive}">맞추러 가기</span>
                </a>
            </div>

            <div class="flex items-start gap-4 p-4 border-b border-base-300">
                <div class="w-28 font-medium">자동 수집</div>
                <div class="grow">
                    <!--/* 파일로 올리는 방식은 자동으로 돌지 않는다. 시각을 물어보면 정해 놓고
                           안 도는 상태가 되어 «왜 안 오지» 가 된다. */-->
                    <div th:unless="${view.schedulable}" class="text-sm text-base-content/70">
                        파일로 올리는 방식이라 자동으로 가져오지 않습니다.
                    </div>
                    <form th:if="${view.schedulable}" method="post"
                          th:action="@{/connectors/{id}/schedule(id=${view.id})}"
                          class="flex items-center gap-2 flex-wrap">
                        <span class="text-sm">매일</span>
                        <select name="hour" class="select select-bordered select-sm w-20">
                            <option value="">안 함</option>
                            <option th:each="h : ${#numbers.sequence(0, 23)}" th:value="${h}"
                                    th:text="${h} + '시'"
                                    th:selected="${view.scheduled and h == view.scheduleHour}">0시</option>
                        </select>
                        <select name="minute" class="select select-bordered select-sm w-20">
                            <option th:each="m : ${ {0, 10, 20, 30, 40, 50} }" th:value="${m}"
                                    th:text="${m} + '분'"
                                    th:selected="${view.scheduled and m == view.scheduleMinute}">0분</option>
                        </select>
                        <button type="submit" class="btn btn-sm">저장</button>
                    </form>
                </div>
            </div>

            <!--/* 적재가 단위 때문에 막히면 여기로 와서 푼다. 지금은 실패를 본 화면에서 해결
                   화면으로 가는 길이 없어 사이드바에서 스스로 찾아야 했다. */-->
            <div class="flex items-center gap-4 p-4 border-b border-base-300">
                <div class="w-28 font-medium">단위 환산</div>
                <div class="grow text-sm text-base-content/70">
                    원천이 「박스」로 보내는데 우리가 「낱개」로 센다면 여기서 규칙을 정합니다.
                </div>
                <a th:href="@{/connectors/units}" class="btn btn-ghost btn-sm">규칙 관리</a>
            </div>

            <div class="flex items-center gap-4 p-4">
                <div class="w-28 font-medium">실행 이력</div>
                <div class="grow text-sm">
                    <span th:if="${view.lastRunAt == null}" class="text-base-content/50">아직 실행한 적 없습니다</span>
                    <span th:if="${view.lastRunAt != null}"
                          th:text="${#temporals.format(view.lastRunAt, 'MM-dd HH:mm')} + ' · 성공 '
                                   + ${view.lastSuccess} + '건, 실패 ' + ${view.lastFailed} + '건'">기록</span>
                </div>
                <a th:href="@{/connectors/{id}/runs(id=${view.id})}" class="btn btn-ghost btn-sm">이력 보기</a>
            </div>

        </div>
    </div>

</div>
</body>
</html>
```

- [ ] **Step 6: 테스트가 통과하는 것을 확인한다**

```bash
JAVA_HOME=/Users/suhsaechan/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
./gradlew build \
  -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

기대: BUILD SUCCESSFUL.

- [ ] **Step 7: 커밋**

```bash
git status --short
git add palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorDetailView.java \
        palim-web/src/main/java/kr/suhsaechan/palim/web/connector/ConnectorDetailController.java \
        palim-web/src/main/resources/templates/connector/detail.html \
        palim-app/src/test/java/kr/suhsaechan/palim/integration/ConnectorDetailScreenRenderIntegrationTest.java
git commit -m "연동 준비 상태판 — 처음 쓰는 사람이 따라가면 완성되는 흐름 : feat : 시스템별 상세 화면과 자동 수집 시각 설정 추가 https://github.com/Cassiiopeia/palim/issues/68"
```

---

### Task 5: 메뉴를 정리한다

**모든 화면이 갈 자리를 가진 뒤에** 메뉴를 손댄다. 순서를 뒤집으면 고아가 생긴다 — 「단위 환산」
메뉴를 먼저 지웠다면, 시스템별 상세가 없는 동안 그 화면으로 갈 방법이 사라진다.

**Files:**
- Modify: `palim-web/src/main/resources/templates/layout.html`
- Modify: `palim-app/src/test/java/kr/suhsaechan/palim/integration/AllScreensRenderIntegrationTest.java`

**Interfaces:**
- Consumes: `GET /connectors/{id}` (Task 4)
- Produces: 없음

- [ ] **Step 1: 사이드바를 바꾼다**

`palim-web/src/main/resources/templates/layout.html` 의 `<ul class="menu p-2 flex-1">` 블록
전체를 아래로 교체한다.

```html
            <!--/*
              메뉴는 «여정 순서» 다. 「재고 맞추기」 세 항목이 사장님이 걷는 길 그대로이고,
              원천이 몇 개로 늘어나도 메뉴는 안 늘어난다 — 「재고 가져오는 곳」 안에서 행이 늘 뿐이다.

              「시작하기」를 빼고 홈이 그 일을 한다. 순서를 안내하는 화면이 메뉴의 한 항목이면
              여러 기능 중 하나로 보인다.

              동결 화면(재고·매핑·수집·인시던트·채널 설정)은 내비게이션에서 제거했다 — 07-DECISIONS 023.
            */-->
            <ul class="menu p-2 flex-1">
                <li><a th:href="@{/}">홈</a></li>
                <li><a th:href="@{/monitor/notifications}">알림 이력</a></li>

                <li class="menu-title">재고 맞추기</li>
                <li><a th:href="@{/connectors}">재고 가져오는 곳</a></li>
                <li><a th:href="@{/reconcile/units}">품목 맞추기</a></li>
                <li><a th:href="@{/reconcile}">대조 결과</a></li>

                <li class="menu-title">인플루언서</li>
                <li><a th:href="@{/influencer/grades}">등급표</a></li>
                <li><a th:href="@{/influencer/rising}">라이징 레이더</a></li>
                <li><a th:href="@{/influencer/trends}">트렌드 보드</a></li>
                <li><a th:href="@{/influencer/campaigns}">캠페인 관리</a></li>

                <li class="menu-title">설정</li>
                <li><a th:href="@{/settings/notification}">알림 설정</a></li>
                <li><a th:href="@{/settings/system}">시스템 설정</a></li>
                <li><a th:href="@{/settings/account}">계정 설정</a></li>
                <li>
                    <details>
                        <summary>고급</summary>
                        <ul>
                            <li><a th:href="@{/connectors/models}">표준 표</a></li>
                            <li><a th:href="@{/audit}">감사 로그</a></li>
                        </ul>
                    </details>
                </li>
            </ul>
```

- [ ] **Step 2: 전 화면 테스트를 최종 상태로 맞춘다**

`AllScreensRenderIntegrationTest` 의 `@ValueSource` 는 **주소** 목록이므로 메뉴에서 빠진 화면도
그대로 둔다 — 주소는 살아 있고 상세·목록에서 도달하기 때문이다. `/connectors/units`,
`/connectors/models` 는 유지한다.

- [ ] **Step 3: 전체 빌드**

```bash
JAVA_HOME=/Users/suhsaechan/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
./gradlew build \
  -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

기대: BUILD SUCCESSFUL.

- [ ] **Step 4: 설계 판단을 문서에 남긴다**

`docs/07-DECISIONS.md` 맨 아래에 항목을 추가한다. 항목 번호는 파일의 마지막 번호 + 1 을 쓴다.

```markdown
## 0NN. 재고 대조를 최상위 메뉴 그룹으로 올린다

2026-08 피벗(023)에서 **옛 재고 관리 도메인**(sku·order·collector·channel·mapping·incident)을
동결하고 내비게이션에서 제거했다. 이번에 올리는 「재고 맞추기」는 그것과 다른 것이다 — 동결한
것은 재고를 **관리**하던 화면이고, 이번 것은 두 원천의 재고를 **맞춰 보는** 새 모듈
(palim-reconcile)이다.

이름이 겹쳐 혼동될 수 있어 여기 적어 둔다. 동결 도메인 화면은 여전히 메뉴에 없고 코드도
수정하지 않는다.
```

- [ ] **Step 5: 커밋**

```bash
git status --short
git add palim-web/src/main/resources/templates/layout.html docs/07-DECISIONS.md
git commit -m "연동 준비 상태판 — 처음 쓰는 사람이 따라가면 완성되는 흐름 : feat : 메뉴를 여정 순서로 재구성 https://github.com/Cassiiopeia/palim/issues/68"
```

---

## 이 계획을 마치면

- 홈에서 **다음 한 걸음**이 보이고, 맞춰 본 뒤로는 오늘 결과가 보인다
- 목록에서 어느 시스템이 **어디까지 됐는지** 한눈에 보인다
- 시스템 하나를 **따로 손볼 수 있고**, 자동 수집 시각을 정할 수 있다
- 메뉴가 여정 순서가 되고, 원천이 늘어도 메뉴는 안 늘어난다

**아직 남은 것** — 계획 2 에서 다룬다.

- ONEWMS 는 여전히 붙일 수 없다(저장 스위치 문제)
- 칸을 맞춰도 LIVE 는 전 행 실패한다(`source` 를 채우는 코드가 없다)
- 대조 정의를 만들 수 없어 대조가 돌지 않는다
- 연결 화면에 나가는 길이 없다
