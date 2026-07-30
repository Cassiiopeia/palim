/**
 * 인증 도메인.
 *
 * <p>관리자 계정과 비밀번호 해싱을 소유한다. 관리자 계정 1개로 로그인하며 다중 사용자·권한
 * 분리는 범위에 포함하지 않는다(F-09).
 *
 * <p>비밀번호 해싱만 필요하므로 {@code spring-security-crypto}만 의존한다. 필터체인 구성은
 * {@code palim-web}의 책임이다.
 */
package kr.suhsaechan.palim.auth;
