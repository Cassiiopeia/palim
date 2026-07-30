package kr.suhsaechan.palim.common;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import java.util.UUID;

/**
 * UUIDv7(RFC 9562) 생성기.
 *
 * <p>상위 48비트가 밀리초 타임스탬프라 시간순으로 정렬된다. 랜덤 UUIDv4를 기본키로 쓰면
 * B-tree 인덱스 삽입 위치가 매번 무작위여서 페이지 분할·WAL 증가·캐시 미스가 누적되는데,
 * 주문 테이블처럼 계속 적재되는 곳에서 특히 불리하다.
 *
 * <p>생성을 Hibernate에 맡기지 않고 애플리케이션에서 수행하는 이유는, 저장 전에 식별자가
 * 확정되어야 로그·알림 메시지·Outbox 레코드에 즉시 쓸 수 있고, 도메인 모듈 간 참조를
 * ID 값으로만 표현할 수 있기 때문이다.
 */
public final class UuidV7 {

    private static final NoArgGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    public static UUID generate() {
        return GENERATOR.generate();
    }

    private UuidV7() {
    }
}
