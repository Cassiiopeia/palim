package kr.suhsaechan.palim.automation.influencer.domain;

/** 분류 라벨을 붙인 주체. */
public enum LabelSource {

    /** YouTube API 가 준 값 — 확정값이다. */
    API,

    /** AI 가 채널 설명·최근 영상 제목을 보고 추론한 값. confidence 를 함께 남긴다. */
    AI
}
