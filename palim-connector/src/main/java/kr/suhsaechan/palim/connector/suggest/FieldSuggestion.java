package kr.suhsaechan.palim.connector.suggest;

import java.util.List;

/**
 * 원천 칸 하나를 어디에 연결할지에 대한 추천.
 *
 * <p>{@code reasons} 를 함께 남기는 이유는 화면이 <b>"왜 이걸 골랐는지"</b> 를 말할 수 있어야
 * 하기 때문이다. 근거 없이 채워진 칸은 사람이 확인하지 않고 넘어가고, 틀렸을 때 어디를 봐야
 * 하는지도 알 수 없다.
 *
 * @param sourceField    원천이 준 칸 이름
 * @param targetFieldKey 우리 표준 항목
 * @param score          근거 점수 합계. 높을수록 확실하다
 * @param reasons        사람이 읽는 근거 ("전에 3번 이렇게 연결하셨습니다")
 */
public record FieldSuggestion(String sourceField, String targetFieldKey, int score,
                              List<String> reasons) {

    public FieldSuggestion {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
