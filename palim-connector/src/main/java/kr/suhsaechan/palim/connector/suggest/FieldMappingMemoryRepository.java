package kr.suhsaechan.palim.connector.suggest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 연결 기억 저장소.
 *
 * <p>테넌트 격리는 Hibernate 필터가 자동으로 건다. 조회 메서드에 tenantId 를 넣지 않는 이유다.
 */
public interface FieldMappingMemoryRepository extends JpaRepository<FieldMappingMemory, UUID> {

    /** 이 이름을 전에 어디에 연결했나. 자주 쓴 것부터. */
    List<FieldMappingMemory> findBySourceFieldAndTargetModelOrderByHitCountDesc(
            String sourceField, String targetModel);

    /** 같은 연결이 이미 있는가. 있으면 횟수만 올린다. */
    Optional<FieldMappingMemory> findBySourceFieldAndTargetModelAndTargetField(
            String sourceField, String targetModel, String targetField);
}
