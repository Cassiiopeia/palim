rootProject.name = "palim"

// 공통
include("palim-common")

// 도메인 — 서로를 의존하지 않는다
include("palim-audit")
include("palim-auth")
include("palim-sku")
include("palim-order")
include("palim-channel")
include("palim-mapping")
include("palim-notification")
include("palim-incident")

// 연동 — 외부 데이터를 표준 모델로 들이는 범용 엔진. 도메인을 모른다.
include("palim-connector")

// 조율 — 여러 도메인을 의존한다
include("palim-automation")  // 외부(유튜브·AI) -> 내부 + 알림 (자동화 모듈)
include("palim-collector")   // 외부 -> 내부 (채널 주문 수집)
include("palim-monitor")     // 내부 -> 알림 (상태 점검)
include("palim-web")

// 실행
include("palim-app")
