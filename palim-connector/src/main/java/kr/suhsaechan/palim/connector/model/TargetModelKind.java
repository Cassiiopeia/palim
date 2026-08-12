package kr.suhsaechan.palim.connector.model;

/**
 * 목표 모델의 성격.
 *
 * <p>{@code BUILTIN} 위에만 도메인 기능(대사·리포트·알림)을 미리 만들 수 있다. 커스텀 모델은
 * 수집·변환·저장·조회까지고, 그 위의 판단 로직이 필요해지면 기본 제공 모델로 승격시킨다.
 * 이 경계를 흐리면 아무 데도 쓸 수 없는 추상화가 된다.
 */
public enum TargetModelKind {
    BUILTIN,
    CUSTOM
}
