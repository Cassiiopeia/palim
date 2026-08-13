package kr.suhsaechan.palim.connector.source.http;

/**
 * 인증 흐름 검증기.
 *
 * <p>프리셋마다 구현체를 둔다. 새 시스템이 붙어도 화면·저장 구조는 그대로이고 이 인터페이스
 * 하나만 구현하면 된다.
 *
 * <p><b>구현이 예외를 던지지 않게 한다.</b> 실패도 결과의 일부다 — 어느 단계에서 무엇 때문에
 * 막혔는지가 사람이 원하는 답이고, 예외로 빠져나가면 그 정보가 스택트레이스에 묻힌다.
 */
public interface ApiProbe {

    ApiAuthPreset.AuthFlow flow();

    /** 전 단계를 순서대로 실행하고 결과를 모아 돌려준다. */
    ProbeReport probe(ProbeRequest request);
}
