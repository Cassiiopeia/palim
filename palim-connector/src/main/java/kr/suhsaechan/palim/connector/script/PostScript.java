package kr.suhsaechan.palim.connector.script;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.tenant.TenantFilters;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

/**
 * 사장님이 쓴 후처리 스크립트.
 *
 * <p>원문을 그대로 담는다. 규칙 몇 가지로 쪼개 두면 「70g 빼기」 같은 것을 넣었을 때 어느
 * 품목까지 영향을 주는지 규칙만 봐서는 알 수 없다. 어디까지 뭉개지는지는 <b>자료를 보면서
 * 사람이 판단</b>해야 하고, 그러려면 판단의 단위가 규칙이 아니라 <b>글 하나</b>여야 한다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "connector_post_script")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostScript extends BaseTimeEntity {

    /** 넉넉히 잡는다. 무한 반복을 끊는 것이 목적이지 느린 스크립트를 막는 것이 아니다. */
    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID connectorId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** 여러 개를 순서대로 돌린다. 앞 결과를 다음이 이어받는다. */
    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostScriptStatus status;

    /** 지우지 않고 꺼 둘 수 있어야 「이게 문제인가」를 하나씩 꺼보며 찾을 수 있다. */
    @Column(nullable = false)
    private boolean isEnabled;

    @Column(nullable = false)
    private int timeoutMs;

    private PostScript(UUID tenantId, UUID connectorId, String name, String body,
                       int sortOrder, int version) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.connectorId = connectorId;
        this.name = name;
        this.body = body;
        this.sortOrder = sortOrder;
        this.version = version;
        this.status = PostScriptStatus.DRAFT;
        this.isEnabled = true;
        this.timeoutMs = DEFAULT_TIMEOUT_MS;
    }

    public static PostScript draft(UUID tenantId, UUID connectorId, String name, String body,
                                   int sortOrder, int version) {
        return new PostScript(tenantId, connectorId, name, body, sortOrder, version);
    }

    public void edit(String name, String body) {
        this.name = name;
        this.body = body;
    }

    public void activate() {
        this.status = PostScriptStatus.ACTIVE;
    }

    public void archive() {
        this.status = PostScriptStatus.ARCHIVED;
    }

    /** 켜고 끄기. 지우는 것과 다르다 — 껐다 켜며 원인을 좁힐 수 있어야 한다. */
    public void changeEnabled(boolean enabled) {
        this.isEnabled = enabled;
    }

    public void changeOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
