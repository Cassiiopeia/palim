package kr.suhsaechan.palim.automation.influencer.taxonomy;

import java.util.List;
import kr.suhsaechan.palim.common.config.ConfigDefinition;
import kr.suhsaechan.palim.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

/**
 * 자체 카테고리 체계의 초기값.
 *
 * <p>유튜브 기본 카테고리는 15종뿐이고 「인물/블로그」 같은 덩어리에 절반이 몰려서 광고 집행에
 * 쓸 수 없다. 광고주가 실제로 나누는 결(뷰티·육아·캠핑·반려동물)로 다시 짠 것이 이 목록이다.
 *
 * <p>계수는 구독자 1명당 추정 단가(원)다. 뷰티·육아·금융이 높은 것은 구매 전환이 직접적이고
 * 광고 수요가 몰리기 때문이며, 게임·키즈가 낮은 것은 도달은 크지만 전환 경로가 길기 때문이다.
 * 실제 견적이 쌓이면 그 실측값으로 대체한다.
 *
 * <p>목록 전체가 설정값 하나다 — 화면에서 카테고리를 추가·삭제하고 키워드를 손볼 수 있어야
 * 하며, 그때마다 배포하지 않는다.
 */
@Component
public class TaxonomyConfigDefinitions implements ConfigDefinitionProvider {

    private static final String DEFAULT_CATEGORIES = """
            [
              {"code":"beauty","name":"뷰티","coefficient":45.0,"seedKeywords":
                ["쿠션 추천","파운데이션 리뷰","아이섀도우 팔레트","스킨케어 루틴","여드름 피부 관리",
                 "데일리 메이크업","헤어 스타일링","향수 추천","뷰티 하울","립 발색"]},
              {"code":"parenting","name":"육아","coefficient":40.0,"seedKeywords":
                ["육아 브이로그","신생아 용품","이유식 만들기","아기 장난감 추천","유아 교육",
                 "출산 준비물","아기 옷 추천","육아템 리뷰","어린이집 준비","워킹맘 일상"]},
              {"code":"finance","name":"재테크","coefficient":42.0,"seedKeywords":
                ["주식 초보","재테크 방법","적금 추천","부동산 초보","절세 방법",
                 "연금 준비","신용카드 혜택","가계부 정리","투자 공부","파이어족"]},
              {"code":"fashion","name":"패션","coefficient":38.0,"seedKeywords":
                ["데일리룩","코디 추천","옷 하울","신발 리뷰","가방 추천",
                 "체형 커버 코디","계절 아우터","액세서리 추천","남자 코디","쇼핑몰 후기"]},
              {"code":"living","name":"리빙","coefficient":35.0,"seedKeywords":
                ["자취방 인테리어","원룸 꾸미기","살림템 추천","청소 꿀팁","수납 정리",
                 "주방용품 리뷰","홈카페 인테리어","이사 준비","가전 추천","셀프 인테리어"]},
              {"code":"pet","name":"반려동물","coefficient":35.0,"seedKeywords":
                ["강아지 브이로그","고양이 일상","반려동물 사료 추천","애견 용품 리뷰","강아지 훈련",
                 "고양이 장난감","반려견 산책","펫 미용","동물병원 후기","냥집사 일상"]},
              {"code":"car","name":"자동차","coefficient":35.0,"seedKeywords":
                ["신차 리뷰","중고차 구매 팁","자동차 용품","차량 관리","전기차 리뷰",
                 "세차 방법","자동차 보험","카시트 추천","드라이브 코스","주행 영상"]},
              {"code":"camping","name":"캠핑","coefficient":33.0,"seedKeywords":
                ["캠핑 브이로그","캠핑 장비 추천","차박","백패킹","텐트 리뷰",
                 "캠핑 요리","등산 코스","낚시 초보","캠핑 의자 추천","감성 캠핑"]},
              {"code":"fitness","name":"운동","coefficient":32.0,"seedKeywords":
                ["홈트레이닝","다이어트 운동","헬스 초보 루틴","스트레칭","필라테스",
                 "단백질 보충제 리뷰","러닝 브이로그","체지방 감량","운동복 추천","요가 루틴"]},
              {"code":"food","name":"푸드","coefficient":30.0,"seedKeywords":
                ["자취 요리","간단 레시피","밀키트 리뷰","에어프라이어 요리","편의점 신상",
                 "집밥 브이로그","베이킹 초보","다이어트 식단","야식 추천","술안주 레시피"]},
              {"code":"travel","name":"여행","coefficient":30.0,"seedKeywords":
                ["국내 여행 추천","해외 여행 브이로그","숙소 리뷰","항공권 꿀팁","혼자 여행",
                 "당일치기 여행","호캉스","맛집 투어","여행 준비물","캠핑카 여행"]},
              {"code":"selfdev","name":"자기계발","coefficient":30.0,"seedKeywords":
                ["공부 자극","시간 관리","독서 리뷰","자격증 공부","영어 공부 방법",
                 "생산성 앱","노션 활용","취업 준비","이직 준비","습관 만들기"]},
              {"code":"tech","name":"테크","coefficient":28.0,"seedKeywords":
                ["스마트폰 리뷰","노트북 추천","이어폰 비교","스마트워치","모니터 추천",
                 "키보드 리뷰","가성비 전자기기","앱 추천","카메라 리뷰","태블릿 활용"]},
              {"code":"kids","name":"키즈","coefficient":25.0,"seedKeywords":
                ["키즈 콘텐츠","어린이 장난감","동요","놀이 영상","어린이 실험",
                 "키즈 카페","색칠놀이","어린이 만들기","학습 놀이","유아 영어"]},
              {"code":"game","name":"게임","coefficient":22.0,"seedKeywords":
                ["게임 공략","신작 게임 리뷰","모바일 게임 추천","게임 실황","인디 게임",
                 "게임 장비 추천","스팀 게임 추천","게임 랭킹","e스포츠 분석","게임 뉴스"]}
            ]
            """;

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(ConfigDefinition.json(TaxonomyConfigKeys.CATEGORIES, DEFAULT_CATEGORIES,
                TaxonomyConfigKeys.CATEGORY, "카테고리 체계",
                "자체 카테고리 목록. code 는 분류·단가 계수의 식별자이고, seedKeywords 는 발굴 "
                        + "검색에 쓰인다. 키워드는 롱테일일수록(\"쿠션 추천\") 중견 채널이 잡히고, "
                        + "넓은 말(\"뷰티\")일수록 이미 큰 채널만 나온다. coefficient 는 구독자 "
                        + "1명당 추정 단가(원)다.", 1));
    }
}
