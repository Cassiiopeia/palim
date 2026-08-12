package kr.suhsaechan.palim.connector.source;

import java.util.stream.Stream;
import kr.suhsaechan.palim.connector.define.SourceType;

/**
 * 원천 어댑터.
 *
 * <p>구현체를 추가해도 파이프라인 뒷단(매핑·변환·적재·실행 이력)은 바뀌지 않는다. DB 직결·
 * FTP·이메일 첨부·웹훅 수신이 붙어도 이 인터페이스 하나만 구현하면 된다.
 *
 * <p>{@link #readSchema} 와 {@link #read} 를 나눈 이유는 <b>연동을 설정할 때와 실제로 돌 때
 * 필요한 것이 다르기 때문</b>이다. 설정 시에는 컬럼 목록과 샘플 몇 행이면 되고, 실행 시에는
 * 전체를 흘려보내야 한다. 하나로 합치면 매핑 화면을 열 때마다 수만 행을 읽는다.
 */
public interface SourceReader {

    SourceType type();

    /** 필드 목록 + 샘플 몇 행. 매핑 편집기와 드리프트 대조에 쓴다. */
    SourceSchema readSchema(SourceContext context);

    /** 전체(또는 증분 구간). 호출자가 청크로 나눠 소비한다. */
    Stream<SourceRow> read(SourceContext context);
}
