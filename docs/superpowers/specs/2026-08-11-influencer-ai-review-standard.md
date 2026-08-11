# AI 심층 심사 기준 (AI Review Standard)

> 인플루언서 등급표 AI 30점의 판단 기준. 설계 원본은
> `2026-08-11-influencer-grading-design.md` §5·§6 이며 이 문서가 그 실행 규격이다.
> 프롬프트 문구·스키마·예시가 바뀌면 `promptVersion` 을 올리고 기존 점수는 재계산 대상이 된다.

## 0. 이 문서가 푸는 문제

AI 에게 "이 인플루언서 어때?"라고 물으면 **그럴듯하지만 검증 불가능한 문장**이 나온다.
광고 캐스팅은 한 번 실패에 수백만원이 날아가므로 그런 출력은 쓸 수 없다.

설득력 있는 판단을 만드는 것은 페르소나가 아니라 **구조**다. 아래 5개 장치가 그 구조다.

| 장치 | 막는 실패 |
|---|---|
| ① 반대 논거 선행 | 점수를 먼저 정하고 사후 정당화하는 것 |
| ② 근거 인용 강제 | "느낌상 괜찮아 보임" 류의 검증 불가 주장 |
| ③ 관찰/추측 분리 | 추론을 사실처럼 제시하는 것 |
| ④ 신뢰도 자기신고 | 자막이 없는데도 있는 것처럼 단정하는 것 |
| ⑤ few-shot 2종 | 기준 없이 후하거나 박하게 매기는 것 |

## 1. 역할 정의 (페르소나)

```
너는 국내 인플루언서 마케팅 캐스팅 심사역이다.
광고주를 대신해 "이 채널에 광고를 맡겨도 되는가"를 검토하고 근거를 정리한다.

너는 집행 여부를 결정하지 않는다. 결정은 사람이 한다.
너의 산출물은 결정권자가 5분 안에 읽고 판단할 수 있는 근거 묶음이다.
```

역할 정의는 **여기까지만** 한다. "15년 경력의 베테랑" 같은 수식은 판단 정확도를 올리지 않고
장식적인 문장만 늘린다.

## 2. 판단 원칙 (프롬프트에 그대로 들어간다)

```
1. 모든 주장에는 원문 인용을 붙인다. 인용할 수 없으면 그 주장을 하지 않는다.
2. 원문에서 직접 읽은 것(observed)과 그로부터 추론한 것(inferred)을 반드시 구분한다.
3. 점수를 매기기 전에, 이 채널에 광고를 주면 안 되는 이유부터 찾는다.
4. 인기와 적합도를 혼동하지 않는다. 조회수가 높은 것은 이미 룰 점수가 반영했다.
   너는 "숫자로는 안 보이는 것"만 본다.
5. 사람을 평가하지 말고 광고 적합도를 평가한다. 인격·외모·사생활에 대한 판단은 쓰지 않는다.
6. 자료가 부족하면 부족하다고 말한다. 빈 곳을 상상으로 채우지 않는다.
```

원칙 4가 중요하다 — AI 가 구독자·조회수를 다시 칭찬하기 시작하면 룰 점수와 이중 계산이 되어
30점이 무의미해진다. **AI 는 자막·댓글에서만 판단 근거를 가져온다.**

원칙 5는 명예훼손 방어선이다. 최종 판단은 사람이 하고, AI 는 확인이 필요한 지점을 표시할 뿐이다.

## 3. 입력 구성

| 항목 | 내용 | 없을 때 |
|---|---|---|
| 캠페인 브리프 | 제품 카테고리·타깃·소구점·금지 조건 | 심사 불가(캠페인 없이 채점하지 않는다) |
| 채널 개요 | 제목·설명·자체 카테고리 라벨 | — |
| 최근 롱폼 5편 | 제목·게시일·유료광고 표시 여부 | — |
| 자막 | 편당 최대 8,000자로 절단 | `자막 없음` 명시 + 신뢰도 하향 |
| 댓글 (최신순 50 × 5편) | 본문 + 좋아요 수 | 댓글 차단 신호로 별도 전달 |
| 댓글 (인기순 50 × 5편) | 본문 + 좋아요 수 | 상동 |

**전송 전 마스킹**: 댓글 본문의 `@핸들` 패턴은 `@사용자` 로 치환한다. 작성자 정보는 애초에
저장하지 않으므로 전송 대상이 아니다.

## 4. 출력 스키마 (구조화 출력, json_schema)

```json
{
  "type": "object",
  "required": ["risks", "brandSafety", "campaignFit", "audienceQuality", "verdict", "confidence"],
  "additionalProperties": false,
  "properties": {
    "risks": {
      "description": "반드시 먼저 작성한다. 광고를 주면 안 되는 이유. 없으면 빈 배열.",
      "type": "array",
      "maxItems": 5,
      "items": {
        "type": "object",
        "required": ["claim", "basis", "evidence", "severity"],
        "additionalProperties": false,
        "properties": {
          "claim": {"type": "string", "maxLength": 200},
          "basis": {"type": "string", "enum": ["observed", "inferred"]},
          "evidence": {
            "type": "array", "minItems": 1, "maxItems": 3,
            "items": {
              "type": "object",
              "required": ["source", "quote"],
              "additionalProperties": false,
              "properties": {
                "source": {"type": "string", "enum": ["transcript", "comment", "metadata"]},
                "quote": {"type": "string", "maxLength": 300}
              }
            }
          },
          "severity": {"type": "string", "enum": ["low", "medium", "high"]}
        }
      }
    },
    "brandSafety":     {"$ref": "#/$defs/scored", "description": "0~12"},
    "campaignFit":     {"$ref": "#/$defs/scored", "description": "0~10"},
    "audienceQuality": {"$ref": "#/$defs/scored", "description": "0~8"},
    "verdict": {
      "type": "object",
      "required": ["headline", "recommend", "conditions"],
      "additionalProperties": false,
      "properties": {
        "headline": {"type": "string", "maxLength": 300,
                     "description": "결정권자가 읽을 3문장 이내 요약"},
        "recommend": {"type": "string", "enum": ["propose", "conditional", "hold", "avoid"]},
        "conditions": {"type": "array", "maxItems": 3, "items": {"type": "string"},
                       "description": "conditional 일 때 붙는 조건. 아니면 빈 배열"}
      }
    },
    "confidence": {
      "type": "string", "enum": ["low", "medium", "high"],
      "description": "자막 수집 실패·댓글 부족·표본 부족 시 반드시 낮춘다"
    }
  },
  "$defs": {
    "scored": {
      "type": "object",
      "required": ["score", "reasons", "evidence"],
      "additionalProperties": false,
      "properties": {
        "score": {"type": "number"},
        "reasons": {"type": "array", "minItems": 1, "maxItems": 3,
                    "items": {"type": "string", "maxLength": 200}},
        "evidence": {
          "type": "array", "minItems": 1, "maxItems": 3,
          "items": {
            "type": "object",
            "required": ["source", "quote"],
            "additionalProperties": false,
            "properties": {
              "source": {"type": "string", "enum": ["transcript", "comment", "metadata"]},
              "quote": {"type": "string", "maxLength": 300}
            }
          }
        }
      }
    }
  }
}
```

`risks` 를 스키마의 **첫 필드**로 둔 것은 의도적이다. 구조화 출력은 순서대로 생성되므로
반대 논거가 점수보다 먼저 쓰이고, 그 내용이 뒤따르는 점수에 반영된다.

## 5. 코드 검증 (AI 가 통과해야 하는 관문)

스키마를 지켜도 무의미한 출력이 나올 수 있어 코드가 다시 거른다.

| 검증 | 위반 시 처리 |
|---|---|
| `evidence[].quote` 가 실제 입력 텍스트에 포함되는가 (공백 정규화 후 부분 일치) | 해당 근거 폐기. 근거가 모두 폐기되면 **그 항목 점수를 중립값(만점의 60%)으로 대체**하고 화면에 "근거 검증 실패" 표시 |
| 각 항목 점수가 배점 범위 안인가 | 범위로 클램프 |
| `recommend=conditional` 인데 `conditions` 가 비었는가 | `hold` 로 강등 |
| 자막이 하나도 없는데 `confidence=high` 인가 | `medium` 으로 하향 |
| `risks` 에 `severity=high` 가 있는데 `brandSafety` 가 만점인가 | 화면에 불일치 경고 표시(점수는 유지, 사람이 판단) |

**인용 검증이 이 시스템의 핵심 방어선이다.** 존재하지 않는 댓글을 지어내면 그 주장은 자동으로
사라진다.

## 6. 항목별 채점 기준

### 브랜드 안전성 (0~12) — 감점형

만점에서 시작해 신호가 발견될 때마다 깎는다.

| 신호 | 감점 |
|---|---|
| 최신 댓글에 영상 주제와 무관한 비난·해명 요구가 몰려 있음 | −4 ~ −8 |
| 사과·해명 영상이 최근 5편 안에 있음 | −4 |
| 댓글이 차단되어 있음 | −3 |
| 자막에 과장 광고 표현("100% 효과", "부작용 없음", "무조건") | −2 ~ −4 |
| 자막에 정치·종교·젠더 이슈에 대한 단정적 발언 | −2 ~ −5 |
| 캠페인 브리프의 금지 조건에 해당 (경쟁사 광고 이력 등) | −3 ~ −6 |
| 유료광고 표시가 최근 5편 중 3편 이상 | −2 |

### 캠페인 적합도 (0~10)

| 수준 | 점수 | 판단 |
|---|---|---|
| 제품 카테고리를 이미 다루고 시청자가 그 주제로 반응함 | 9~10 | 인접이 아니라 정확히 일치 |
| 인접 카테고리이고 자연스럽게 녹일 수 있음 | 6~8 | |
| 결이 다르지만 소구점 하나는 통함 | 3~5 | |
| 채널 주제와 제품이 충돌 | 0~2 | 시청자가 광고를 배신으로 받아들일 위험 |

### 시청자 반응 품질 (0~8)

| 관찰 | 점수 |
|---|---|
| 구매 의향 표현("어디서 사요", "샀어요", "재구매")이 반복 등장 | 7~8 |
| 내용에 대한 구체적 반응이 주를 이룸 | 5~6 |
| 짧은 응원·이모지 위주 | 3~4 |
| 봇·도배·무의미 댓글 비중이 큼 | 0~2 |

## 7. few-shot 예시 (프롬프트에 2개 모두 포함)

### 예시 A — 추천 케이스

입력 요약: 캠핑 채널, 캠핑용 조리도구 캠페인. 자막 5편 수집, 댓글에 제품 문의 다수.

```json
{
  "risks": [
    {
      "claim": "최근 5편 중 2편이 유료광고라 시청자 피로도가 쌓이는 구간이다",
      "basis": "observed",
      "evidence": [{"source": "metadata", "quote": "유료 광고 포함 (2/5편)"}],
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

### 예시 B — 위험 케이스

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

예시 B 가 보여주는 것: **적합도가 높아도 안전성 때문에 보류가 될 수 있다**는 점, 그리고
`headline` 이 "논란이 있다"가 아니라 **"확인이 필요하다"** 로 끝난다는 점이다. AI 는 사실
확정을 하지 않는다.

## 8. 화면 표시 규격

점수만 보여주면 설득되지 않는다. 이 순서로 노출한다.

```
82점 / A등급 · 조건부 제안        신뢰도 중간 ⓘ 자막 3편 중 1편만 수집됨

▸ 확인 필요 (1)
  ⚠ 최근 5편 중 2편이 유료광고 — 시청자 피로도 (관찰)
     "유료 광고 포함 (2/5편)"                              [원문 보기]

▸ 브랜드 안전성  11/12
  · 논란 신호 없음   · 광고 고지를 영상 초반에 명시
     "이 영상은 협찬을 받아 제작했습니다"                    [원문 보기]
  ...
                                        [🔍 채널명 웹 검색]  ← 최종 확인은 사람
[제안]  [보류]  [제외]
```

- `확인 필요(risks)` 를 **점수보다 위**에 둔다 — 나쁜 소식을 먼저 본다
- 모든 근거에 `[원문 보기]` — 인용이 실제 어디서 왔는지 즉시 확인
- `(관찰)` / `(추측)` 배지로 근거의 무게를 구분
- 웹 검색 버튼으로 사람이 마지막 확인을 한다

## 9. 재현성

`inputHash = sha256(channelId ‖ 정렬된 videoId 목록 ‖ 자막 해시 ‖ 댓글 해시 ‖ promptVersion ‖
rubricVersion ‖ campaignId)`

해시가 같으면 AI 를 호출하지 않고 기존 점수를 그대로 쓴다. 같은 채널을 다시 열었을 때 점수가
달라 보이면 그 순간 신뢰를 잃기 때문이다. 온도는 0 으로 고정한다.

## 10. 비용 관리

- 심층 심사는 **캠페인당 상위 N명(기본 20)** 에만, 화면에서 사람이 실행한다
- 자막은 편당 8,000자로 절단, 댓글은 편당 최신 50 + 인기 50
- 채널당 입력 약 20k 토큰 예상 — 20명이면 400k
- 실패는 1회만 재시도하고, 두 번째 실패는 `ai_total = null` 로 남긴다(룰 점수만으로 표시)
