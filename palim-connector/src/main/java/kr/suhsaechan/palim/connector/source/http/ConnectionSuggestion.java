package kr.suhsaechan.palim.connector.source.http;

import java.util.List;
import java.util.Locale;

/**
 * 받아온 데이터를 보고 <b>저장 설정을 대신 정해 준다.</b>
 *
 * <p>사람에게 물어도 되는 질문과 물으면 안 되는 질문이 있다. 「이 연결을 뭐라고 부를까요」는
 * 전자다 — 답이 사람 머릿속에만 있다. 「받은 데이터를 어느 표준 모델에 담을까요」는 후자다 —
 * 답이 이미 데이터 안에 적혀 있다. 수량 칸이 들어 있으면 재고이고, 없으면 품목이다.
 *
 * <p>답이 데이터에 있는데도 사람에게 되물으면, <b>답을 아는 사람만 쓸 수 있는 화면</b>이 된다.
 * 실무자는 "재고 스냅샷이 뭔데"에서 멈춘다. 그래서 여기서 정하고, 화면은 결과만 보여준 뒤
 * 바꾸고 싶은 사람에게만 바꿀 길을 남긴다.
 */
public final class ConnectionSuggestion {

    /**
     * 수량으로 볼 만한 칸 이름 조각.
     *
     * <p>시스템마다 이름이 다르지만(BAL_QTY·STOCK·재고수량) "수량"이라는 뜻은 이름에 드러난다.
     * 완벽히 맞히지 못해도 손해가 없다 — 틀리면 화면에서 바꾸면 되고, 대부분은 맞는다.
     */
    private static final List<String> QUANTITY_HINTS =
            List.of("qty", "quantity", "stock", "bal", "수량", "재고");

    /** 입출고 이력으로 볼 만한 칸 이름 조각. 날짜와 증감이 함께 오면 이력이다. */
    private static final List<String> MOVEMENT_HINTS =
            List.of("in_qty", "out_qty", "입고", "출고", "io_type");

    private ConnectionSuggestion() {
    }

    /**
     * 어느 표준 모델에 담을지.
     *
     * @param fields 원천이 실제로 돌려준 칸 이름들
     */
    public static String targetModel(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return "std_stock_snapshot";
        }
        List<String> lower = fields.stream().map(f -> f.toLowerCase(Locale.ROOT)).toList();
        if (containsAny(lower, MOVEMENT_HINTS)) {
            return "std_stock_movement";
        }
        if (containsAny(lower, QUANTITY_HINTS)) {
            return "std_stock_snapshot";
        }
        // 수량이 없으면 담을 자리가 없다. 품목 정보로 본다.
        return "std_item";
    }

    /** 사람이 읽는 이름. 비워 두고 직접 쓰게 하면 대부분 「테스트」라고 적고 잊는다. */
    public static String name(ApiAuthPreset preset, String targetModel) {
        return "%s %s".formatted(preset.getLabel(), modelWord(targetModel));
    }

    /**
     * 내부 식별자.
     *
     * <p>사람이 정할 이유가 없는 값이다. 「영문 소문자·숫자·하이픈」 같은 제약을 사용자에게
     * 설명하는 순간, 그 화면은 규칙을 아는 사람의 것이 된다.
     */
    public static String code(ApiAuthPreset preset, String targetModel) {
        return "%s-%s".formatted(preset.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                modelSlug(targetModel));
    }

    /** 화면이 "무엇으로 저장되는지" 한 줄로 말할 때 쓴다. */
    public static String modelWord(String targetModel) {
        return switch (targetModel) {
            case "std_stock_snapshot" -> "재고";
            case "std_stock_movement" -> "입출고 이력";
            case "std_outbound_order" -> "출고 주문";
            default -> "품목";
        };
    }

    private static String modelSlug(String targetModel) {
        return switch (targetModel) {
            case "std_stock_snapshot" -> "stock";
            case "std_stock_movement" -> "movement";
            case "std_outbound_order" -> "outbound";
            default -> "item";
        };
    }

    private static boolean containsAny(List<String> fields, List<String> hints) {
        return fields.stream().anyMatch(field -> hints.stream().anyMatch(field::contains));
    }
}
