package kr.suhsaechan.palim.automation.influencer.transcript;

/** 자막 추출 결과. */
public record TranscriptResult(TranscriptStatus status, String language, String content) {

    public static TranscriptResult none() {
        return new TranscriptResult(TranscriptStatus.NONE, null, null);
    }

    public static TranscriptResult blocked() {
        return new TranscriptResult(TranscriptStatus.BLOCKED, null, null);
    }

    public boolean hasContent() {
        return status == TranscriptStatus.OK && content != null && !content.isBlank();
    }
}
