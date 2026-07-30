package kr.suhsaechan.palim.common;

/**
 * 판매 채널 식별자.
 *
 * <p>주문·매핑·재고 전송 이력이 모두 이 값을 참조하므로 특정 도메인 모듈이 아니라 공통 모듈에 둔다.
 * 도메인 모듈끼리 서로를 의존하지 않는다는 규칙을 지키려면 공유 값 타입은 여기 있어야 한다.
 *
 * <p>카카오 선물하기·톡스토어는 카카오가 연동 대행사를 선정하는 구조라 승인 시점을 통제할 수 없어
 * 범위에서 제외했다(기능 명세서 F-01).
 */
public enum ChannelCode {

    /** 공식 API (HMAC-SHA256). 초당 10회, 지속 초과 시 영구 차단. */
    COUPANG("쿠팡"),

    /** 공식 API (OAuth2 + 전자서명). 초당 2회. */
    NAVER("네이버 스마트스토어"),

    /** 공식 API (인증키). 호출 제한 미공개. */
    LOTTEON("롯데온"),

    /** 공식 API (API Key). 호출 제한 미공개. */
    ELEVENST("11번가"),

    /** 공식 API (JWT). 주문조회 5초당 1회. */
    ESM("G마켓/옥션"),

    /** 입점 및 MD 승인 선행 필요. */
    SSG("SSG닷컴"),

    /** 공식 API가 없어 엑셀 업로드로만 처리한다. */
    LOTTE_DEPT("롯데백화점");

    private final String displayName;

    ChannelCode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
