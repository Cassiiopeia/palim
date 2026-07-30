package kr.suhsaechan.palim.channel.adapter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.ChannelCode;

/**
 * 채널별 주문 수집 어댑터.
 *
 * <p>구현체는 채널 공식 API 를 호출해 주문을 가져오고 {@link ChannelOrder} 로 변환한다.
 * <b>도메인 엔티티를 알지 못하며 데이터베이스에 접근하지 않는다.</b> 순수하게 "채널에서 읽어
 * 공통 형식으로 바꾸는" 역할만 한다.
 *
 * <p>구현 시 지켜야 할 것
 *
 * <ul>
 *   <li>주문 시각을 {@link Instant} 로 정규화한다. 채널이 KST 로 주면 변환해야 한다 —
 *       타임존 모호성이 유입되면 중복 판정과 커서 계산이 어긋나 재고가 이중 차감된다
 *   <li>채널 호출 제한을 준수한다. 쿠팡은 지속 초과 시 <b>영구 차단</b>된다
 *   <li>페이징이 있으면 전체를 순회해 반환한다. 부분만 반환하면 주문이 누락된다
 *   <li>인증 실패·호출 제한 초과는 예외로 던진다. 빈 목록을 반환하면 조율 계층이
 *       "주문이 없다"로 오판해 커서를 전진시키고, 그 구간의 주문이 영구 유실된다
 * </ul>
 */
public interface ChannelOrderCollector {

    /** 이 어댑터가 담당하는 채널. */
    ChannelCode channelCode();

    /**
     * 지정 구간의 주문을 수집한다.
     *
     * <p>구간은 조율 계층이 겹치게 계산해 넘긴다(설계서 5.4). 어댑터는 받은 구간을 그대로
     * 조회하면 되고, 중복 제거를 신경 쓰지 않는다 — 그건 데이터베이스 유니크 제약의 몫이다.
     *
     * @param from        조회 시작(포함)
     * @param to          조회 종료(포함)
     * @param credentials 복호화된 인증정보. 키 구성은 채널마다 다르다
     * @return 수집된 주문. 없으면 빈 목록
     */
    List<ChannelOrder> collect(Instant from, Instant to, Map<String, String> credentials);
}
