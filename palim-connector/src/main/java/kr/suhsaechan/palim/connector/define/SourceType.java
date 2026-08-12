package kr.suhsaechan.palim.connector.define;

/**
 * 원천 유형.
 *
 * <p>구현체를 추가해도 파이프라인 뒷단은 바뀌지 않는다. DB 직결·FTP·이메일 첨부·웹훅 수신이
 * 붙어도 {@code SourceReader} 하나를 구현하면 된다.
 */
public enum SourceType {
    /** 사람이 올린 파일(엑셀·CSV·JSON). API 가 막혀도 업무가 멈추지 않는 경로다. */
    UPLOAD,
    /** REST 응답. */
    HTTP_API
}
