package kr.suhsaechan.palim.web.connector;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.script.PostScript;
import kr.suhsaechan.palim.connector.script.PostScriptRepository;
import kr.suhsaechan.palim.connector.script.PostScriptStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 후처리 스크립트 관리.
 *
 * <p>매핑과 같은 규약을 쓴다 — 고치면 <b>새 버전</b>이 되고, 확정해야 실제로 돈다. 덮어쓰지
 * 않는 이유는 나중에 「이 이름이 왜 이렇게 됐지」 를 설명해야 하기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class PostScriptAdminService {

    private final PostScriptRepository repository;

    /** 화면 목록. 꺼 둔 것도 보여야 켜고 끌 수 있다. */
    @Transactional(readOnly = true)
    public List<PostScript> active(UUID connectorId) {
        return repository.findByConnectorIdAndStatusOrderBySortOrder(
                connectorId, PostScriptStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public PostScript get(UUID scriptId) {
        return repository.findById(scriptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MAPPING_NOT_FOUND));
    }

    /**
     * 저장하면 곧바로 확정한다.
     *
     * <p>매핑처럼 초안·확정을 나누지 않는 이유는, 스크립트의 확인 수단이 <b>시험 실행</b>이기
     * 때문이다. 시험은 진짜 자료에 닿지 않으므로 확정 상태로 두어도 위험하지 않고, 단계를
     * 하나 더 두면 「저장했는데 왜 안 돌지」 가 생긴다 — 오늘 매핑에서 겪은 그 함정이다.
     *
     * <p>대신 <b>고칠 때마다 버전이 오른다.</b> 지난 것은 보관되어 되돌릴 수 있다.
     */
    @Transactional
    public PostScript save(UUID tenantId, UUID connectorId, UUID scriptId, String name,
                           String body) {
        if (scriptId != null) {
            PostScript previous = get(scriptId);
            previous.archive();
            repository.save(previous);

            PostScript next = PostScript.draft(tenantId, connectorId, name, body,
                    previous.getSortOrder(), previous.getVersion() + 1);
            next.changeEnabled(previous.isEnabled());
            next.activate();
            return repository.save(next);
        }
        int order = active(connectorId).size() + 1;
        PostScript created = PostScript.draft(tenantId, connectorId, name, body, order,
                nextVersion(connectorId, name));
        created.activate();
        return repository.save(created);
    }

    private int nextVersion(UUID connectorId, String name) {
        return repository.findByConnectorIdAndNameOrderByVersionDesc(connectorId, name).stream()
                .mapToInt(PostScript::getVersion)
                .max()
                .orElse(0) + 1;
    }

    /** 켜고 끄기. 지우는 것과 다르다 — 껐다 켜며 원인을 좁힐 수 있어야 한다. */
    @Transactional
    public void changeEnabled(UUID scriptId, boolean enabled) {
        PostScript script = get(scriptId);
        script.changeEnabled(enabled);
        repository.save(script);
    }

    /**
     * 순서 바꾸기.
     *
     * <p>한 칸씩 자리를 맞바꾼다. 위에서부터 차례로 돌고 앞 결과를 다음이 이어받으므로,
     * 순서가 곧 처리 순서다.
     */
    @Transactional
    public void move(UUID connectorId, UUID scriptId, int delta) {
        List<PostScript> scripts = active(connectorId);
        int index = -1;
        for (int i = 0; i < scripts.size(); i++) {
            if (scripts.get(i).getId().equals(scriptId)) {
                index = i;
                break;
            }
        }
        int target = index + delta;
        if (index < 0 || target < 0 || target >= scripts.size()) {
            return;
        }
        PostScript moved = scripts.get(index);
        PostScript swapped = scripts.get(target);
        int order = moved.getSortOrder();
        moved.changeOrder(swapped.getSortOrder());
        swapped.changeOrder(order);
        repository.saveAll(List.of(moved, swapped));
    }

    /** 통째로 순서를 다시 매긴다. 끌어다 놓았을 때 화면이 보내는 그대로. */
    @Transactional
    public void reorder(UUID connectorId, List<UUID> orderedIds) {
        List<PostScript> scripts = active(connectorId);
        for (PostScript script : scripts) {
            int position = orderedIds.indexOf(script.getId());
            if (position >= 0) {
                script.changeOrder(position + 1);
            }
        }
        repository.saveAll(scripts);
    }

    /**
     * 지우기.
     *
     * <p>실제로 없애지 않고 <b>보관으로 내린다.</b> 이 스크립트로 다듬어진 자료가 이미 담겨
     * 있고, 실행 기록이 이 스크립트를 가리킨다. 없애면 「그때 무엇으로 다듬었는지」 를 설명할
     * 방법이 사라진다.
     */
    @Transactional
    public void remove(UUID scriptId) {
        PostScript script = get(scriptId);
        script.archive();
        repository.save(script);
    }
}
