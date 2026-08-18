package kr.suhsaechan.palim.reconcile.match;

import java.util.List;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「이 품목은 짝이 없다」 표시를 붙이고 뗀다.
 *
 * <p>이 표시가 있어야 할 일 개수가 <b>0에 도달한다.</b> 도달하지 못하는 숫자는 사람이 곧
 * 안 보게 되고, 안 보는 숫자는 없는 것과 같다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnpairedService {

    private final UnpairedItemRepository repository;

    /** 이미 표시돼 있으면 이유만 고친다 — 두 번 눌러도 같은 결과가 되어야 한다. */
    @Transactional
    public UnpairedItem setAside(String source, String itemRef, UnpairedItem.Reason reason,
                                 String note) {
        return repository.findBySourceAndItemRef(source, itemRef)
                .map(existing -> {
                    existing.update(reason, note);
                    return repository.save(existing);
                })
                .orElseGet(() -> repository.save(UnpairedItem.of(
                        TenantContext.current(), source, itemRef, reason, note)));
    }

    /** 표시를 뗀다. 없으면 아무 일도 하지 않는다. */
    @Transactional
    public void restore(String source, String itemRef) {
        repository.findBySourceAndItemRef(source, itemRef).ifPresent(repository::delete);
    }

    @Transactional(readOnly = true)
    public List<UnpairedItem> of(List<String> sources) {
        return repository.findBySourceInOrderBySourceAscItemRefAsc(sources);
    }
}
