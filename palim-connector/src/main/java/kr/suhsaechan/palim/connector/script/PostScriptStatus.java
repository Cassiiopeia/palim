package kr.suhsaechan.palim.connector.script;

/**
 * 스크립트 버전 상태.
 *
 * <p>매핑과 같은 규약이다(초안 → 확정 → 보관). 덮어쓰지 않고 버전을 올리는 이유는 나중에
 * <b>「이 이름이 왜 이렇게 됐지」</b> 를 설명해야 하기 때문이다.
 */
public enum PostScriptStatus { DRAFT, ACTIVE, ARCHIVED }
