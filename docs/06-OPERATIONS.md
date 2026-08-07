# 06. 운영

## 설정 파일 전략 (PUBLIC 레포 전제)

| 파일 | 커밋 | 내용 |
|---|---|---|
| `application.yml` | O | 공통 구조·무해한 기본값. **민감값은 전부 `${환경변수}` placeholder** |
| `application-dev.yml` | **X (gitignore)** | 로컬 개발값 |
| `application-prod.yml` | **X (gitignore)** | 운영값. `-f` 로도 커밋 금지 |
| `*.yml.example` | O | 형태만 — 값은 placeholder |

3중 방어: ① gitignore ② push protection/secret scanning ③ CI `guard` 잡.

비밀값 주입: 로컬 `.env`(gitignore) → 운영은 AWS SSM Parameter Store / Secrets Manager.

## 빌드 · 배포 — Docker 단일 이미지

```
이미지 = JRE + 애플리케이션 jar + python3 + pip 의존성(requirements.txt) + yt-dlp
```

- py 의존성은 `scripts/requirements.txt` 로 버전 고정 — 시스템 파이썬에 의존하지 않는다
- 로컬 Gradle 배포판 다운로드가 막히면 push 하고 GitHub Actions 로 검증한다 (의도된 구조)

## AWS 이식 경로

| 항목 | 결정 |
|---|---|
| 컴퓨트 | EC2 (t4g.small급) 또는 ECS — 컨테이너 1개면 충분 |
| DB | 초기: 같은 인스턴스 PostgreSQL 컨테이너 → 필요 시 RDS |
| 비밀 | SSM Parameter Store (환경변수 주입) |
| 인바운드 | 443 만 (+SSH 는 관리자 IP 한정). DB·내부 포트 노출 금지 |
| TLS | ALB/nginx 종단 또는 기존 Cloudflare Tunnel 유지 |

서버 실비(인스턴스+스토리지)는 발주사에 투명 고지 — 마진 없음.

## 데이터 · 백업

- 업로드 원본은 `data/`(gitignore·컨테이너 볼륨) + 처리 결과는 DB
- DB 일일 백업(pg_dump → 스토리지), 보존 기한은 발주사와 합의
- 리뷰 원문 등 개인정보성 텍스트는 **로그에 남기지 않는다** — 로그엔 건수·ID 만

## 감시 · 장애

- 모듈 실행 실패 → 텔레그램 알림 (palim-notification 경유)
- 스케줄 작업(브리핑 등)이 정시에 안 돌았을 때를 감지하는 하트비트 체크
- py 서브프로세스 타임아웃·비정상 종료는 실행 이력에 기록 + 알림

## 화면 보안 (유지)

CSP·CSRF·세션 방어·감사 로그 등은 09-SECURITY 구현 현황 그대로 유지된다. 새 화면(업로드·
실행·결과)도 같은 규칙을 따른다.
