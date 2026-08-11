## 예시 A — 추천 케이스

입력 요약: 캠핑 채널, 캠핑용 조리도구 캠페인. 자막 5편 수집, 댓글에 제품 문의 다수.

```json
{
  "risks": [
    {
      "claim": "최근 5편 중 2편이 유료광고라 시청자 피로도가 쌓이는 구간이다",
      "basis": "observed",
      "evidence": [{"source": "metadata", "quote": "유료 광고 포함 2/5편"}],
      "severity": "low"
    }
  ],
  "brandSafety": {
    "score": 11,
    "reasons": ["논란 신호가 발견되지 않았다", "광고 고지를 영상 초반에 명시적으로 한다"],
    "evidence": [{"source": "transcript", "quote": "이 영상은 협찬을 받아 제작했습니다"}]
  },
  "campaignFit": {
    "score": 9,
    "reasons": ["캠핑 조리 장면이 매 영상의 중심이라 제품이 자연스럽게 들어간다"],
    "evidence": [{"source": "transcript", "quote": "오늘은 불맛 나는 캠핑 요리를 해볼 건데요"}]
  },
  "audienceQuality": {
    "score": 7,
    "reasons": ["장비 구매 문의가 반복적으로 올라온다"],
    "evidence": [{"source": "comment", "quote": "저 코펠 어디 제품인가요? 링크 좀요"}]
  },
  "verdict": {
    "headline": "캠핑 조리도구와 결이 정확히 맞고 시청자가 실제로 장비를 구매합니다. 광고 비중이 다소 높은 점만 감안하면 제안 대상입니다.",
    "recommend": "propose",
    "conditions": []
  },
  "confidence": "high"
}
```

## 예시 B — 위험 케이스

입력 요약: 뷰티 채널, 스킨케어 캠페인. 자막 1편만 수집, 최신 댓글에 해명 요구 밀집.

```json
{
  "risks": [
    {
      "claim": "최근 영상 댓글이 영상 내용과 무관한 해명 요구로 채워져 있다",
      "basis": "observed",
      "evidence": [
        {"source": "comment", "quote": "지난번 그 제품 건은 언제 입장 내시나요"},
        {"source": "comment", "quote": "해명 없이 그냥 넘어가시는 건가요?"}
      ],
      "severity": "high"
    },
    {
      "claim": "효과를 단정하는 표현을 쓴다",
      "basis": "observed",
      "evidence": [{"source": "transcript", "quote": "이거 쓰면 트러블 무조건 잡혀요"}],
      "severity": "medium"
    }
  ],
  "brandSafety": {
    "score": 3,
    "reasons": ["외부 이슈로 보이는 해명 요구가 최신 댓글에 밀집해 있다",
                "효과 단정 표현은 광고 심의 위험을 만든다"],
    "evidence": [{"source": "comment", "quote": "해명 없이 그냥 넘어가시는 건가요?"}]
  },
  "campaignFit": {
    "score": 7,
    "reasons": ["스킨케어를 상시 다루므로 주제 자체는 맞는다"],
    "evidence": [{"source": "transcript", "quote": "요즘 제 피부 루틴 알려드릴게요"}]
  },
  "audienceQuality": {
    "score": 3,
    "reasons": ["제품 반응보다 이슈 관련 댓글이 앞선다"],
    "evidence": [{"source": "comment", "quote": "제품 얘기 말고 그 건부터 답해주세요"}]
  },
  "verdict": {
    "headline": "주제 적합도는 높지만 현재 해명 요구가 진행 중으로 보입니다. 무슨 사안인지 확인하기 전에는 집행을 보류해야 합니다.",
    "recommend": "hold",
    "conditions": []
  },
  "confidence": "medium"
}
```

예시 B 가 보여주는 것: 적합도가 높아도 안전성 때문에 보류가 될 수 있고,
headline 이 "논란이 있다"가 아니라 **"확인이 필요하다"** 로 끝난다는 점이다.
