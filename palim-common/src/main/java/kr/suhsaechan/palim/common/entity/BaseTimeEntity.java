package kr.suhsaechan.palim.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 생성·수정 시각을 기록하는 공통 상위 클래스.
 *
 * <p>시각 타입은 {@link Instant}로 고정한다. 채널 API가 KST와 UTC를 섞어서 응답하므로,
 * 주문 시각에 타임존 모호성이 유입되면 중복 판정과 수집 커서 계산이 어긋나고 그것이 곧
 * 재고 이중 차감으로 이어진다. {@code LocalDateTime}은 리포트 출력 직전의 표시 변환에만 쓴다.
 *
 * <p>{@code isEdited} 같은 boolean 플래그는 두지 않는다. {@code createdAt != updatedAt}으로
 * 유도되는 파생 정보이며, 두 값이 어긋날 여지만 만든다.
 *
 * <p>감사자 컬럼({@code @CreatedBy})도 두지 않는다. 관리자 계정이 1개라 모든 행의 값이 동일하다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
