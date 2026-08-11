/**
 * AI 업무자동화 모듈 도메인.
 *
 * <p>재고 도메인 동결(07-DECISIONS 023) 이후 새 기능이 들어가는 유일한 도메인 모듈이다.
 * 1호 하위 도메인은 인플루언서 등급표({@code influencer} 패키지) — 유튜브 공식 API 지표 기반
 * 룰 점수 70 + AI 심사 30, 라이징 지수 100 을 산출한다. 설계 원본은
 * {@code docs/superpowers/specs/2026-08-11-influencer-grading-design.md}.
 */
package kr.suhsaechan.palim.automation;
