package kr.suhsaechan.palim.web.connector;

import java.util.List;

/**
 * 칸 연결 화면의 한 줄.
 *
 * <p>화면이 이 모양을 그대로 그린다. 컨트롤러에서 엔티티를 그대로 넘기면 템플릿이 도메인 구조를
 * 알아야 하고, 화면 문구를 바꿀 때마다 도메인을 건드리게 된다.
 *
 * @param targetKey   저장 컬럼 이름. 폼이 이 값을 돌려보낸다
 * @param label       사람이 읽는 항목 이름
 * @param required    비면 저장되지 않는가
 * @param mode        {@code SELECT} 저쪽 칸을 고름 · {@code AUTO} 시스템이 채움 ·
 *                    {@code CONSTANT} 사람이 적어 넣음 · {@code NONE} 연결 안 함
 * @param picked      고른 저쪽 칸. {@code SELECT} 일 때만
 * @param constant    적어 넣은 값. {@code CONSTANT} 일 때만
 * @param preview     그 칸에 <b>실제로 들어 있는 값</b>. 이것이 이 화면의 핵심이다
 * @param reasons     왜 이걸 골라 뒀는지. 추천으로 채워진 줄에만 있다
 */
public record MappingRowView(String targetKey, String label, boolean required, String mode,
                             String picked, String constant, String preview,
                             List<String> reasons) {

    public MappingRowView {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public boolean isAuto() {
        return "AUTO".equals(mode);
    }

    public boolean isConstant() {
        return "CONSTANT".equals(mode);
    }

    /** 추천으로 채워진 줄인가. 사람이 직접 고른 것과 구분되어야 «내가 안 골랐는데» 가 없다. */
    public boolean isSuggested() {
        return !reasons.isEmpty();
    }
}
