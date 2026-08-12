package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.model.FieldDataType;
import kr.suhsaechan.palim.connector.model.TargetField;
import kr.suhsaechan.palim.connector.model.TargetFieldBootstrap;
import kr.suhsaechan.palim.connector.model.TargetFieldRepository;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 표준 모델 필드 부트스트랩.
 *
 * <p>이 등록이 없으면 매핑 편집기의 오른쪽(목표 필드)이 비어 아무것도 연결할 수 없다.
 * 재기동해도 기존 정의를 덮어쓰지 않아야 화면에서 고친 표시명이 배포에 되돌려지지 않는다.
 */
class TargetFieldBootstrapIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private TargetFieldBootstrap bootstrap;
    @Autowired private TargetModelRepository modelRepository;
    @Autowired private TargetFieldRepository fieldRepository;

    @Test
    @DisplayName("표준 모델 4종에 필드가 등록된다")
    void 표준_모델에_필드가_등록된다() {
        assertThat(fieldCount("std_item")).isPositive();
        assertThat(fieldCount("std_stock_snapshot")).isPositive();
        assertThat(fieldCount("std_stock_movement")).isPositive();
        assertThat(fieldCount("std_outbound_order")).isPositive();
    }

    @Test
    @DisplayName("필수 필드가 필수로 등록된다")
    void 필수_여부가_반영된다() {
        List<TargetField> fields = fieldsOf("std_stock_snapshot");

        assertThat(fields).filteredOn(field -> "base_at".equals(field.getFieldKey()))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.isRequired())
                            .as("시점을 모르는 재고는 대사에 쓸 수 없다").isTrue();
                    assertThat(field.getDataType()).isEqualTo(FieldDataType.TIMESTAMP);
                });
    }

    @Test
    @DisplayName("유통기한은 날짜 타입으로 등록된다")
    void 유통기한은_날짜다() {
        assertThat(fieldsOf("std_stock_snapshot"))
                .filteredOn(field -> "expiry_date".equals(field.getFieldKey()))
                .singleElement()
                .extracting(TargetField::getDataType)
                .isEqualTo(FieldDataType.DATE);
    }

    @Test
    @DisplayName("다시 실행해도 필드가 늘지 않는다 — 재기동이 안전해야 한다")
    void 재실행이_중복을_만들지_않는다() {
        int before = fieldCount("std_stock_snapshot");

        bootstrap.run(null);

        assertThat(fieldCount("std_stock_snapshot")).isEqualTo(before);
    }

    @Test
    @DisplayName("개인정보 필드는 출고 주문 모델에만 있다")
    void 개인정보는_출고주문에만_있다() {
        assertThat(keysOf("std_outbound_order")).contains("receiver_name", "receiver_phone");
        assertThat(keysOf("std_stock_snapshot"))
                .as("재고 조회 화면까지 개인정보 취급 대상이 되면 안 된다")
                .doesNotContain("receiver_name", "receiver_phone");
    }

    private int fieldCount(String modelCode) {
        return fieldsOf(modelCode).size();
    }

    private List<String> keysOf(String modelCode) {
        return fieldsOf(modelCode).stream().map(TargetField::getFieldKey).toList();
    }

    private List<TargetField> fieldsOf(String modelCode) {
        TargetModel model = modelRepository.findByTenantIdAndCode(TENANT, modelCode).orElseThrow();
        return fieldRepository.findByTargetModelIdOrderBySortOrder(model.getId());
    }
}
