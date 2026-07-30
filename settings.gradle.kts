rootProject.name = "palim"

// 공통
include("palim-common")

// 도메인 — 서로를 의존하지 않는다
include("palim-auth")
include("palim-sku")
include("palim-order")
include("palim-channel")
include("palim-mapping")
include("palim-notification")

// 조율 — 여러 도메인을 의존한다
include("palim-collector")   // 외부 -> 내부 (채널 주문 수집)
include("palim-monitor")     // 내부 -> 알림 (상태 점검)
include("palim-web")

// 실행
include("palim-app")
