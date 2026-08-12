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

## 운영 환경 (2026-08 기준)

| 항목 | 값 |
|---|---|
| 호스트 | 시놀로지 NAS (`suh-project.synology.me`, SSH 2022) |
| 컨테이너 | `palim` — `ghcr.io/cassiiopeia/palim:<커밋 SHA>` |
| 포트 | 호스트 `8095` → 컨테이너 `8080` |
| DB | **NAS 공용 PostgreSQL 14.15** 의 `palim` 데이터베이스, 전용 계정 `palim` |
| 네트워크 | `postgres_default` — DB 컨테이너와 같은 네트워크라 `postgres:5432` 로 붙는다 |
| 외부 노출 | DSM 역방향 프록시 → `https://palim.suhsaechan.kr` |

**DB 는 여러 프로젝트가 공유한다.** 그래서 두 가지를 지킨다 — 커넥션 풀 상한을 10 으로 묶고,
`palim` 전용 계정만 쓴다(공용 관리자 계정을 쓰면 남의 DB 까지 접근 가능해진다).

**PostgreSQL 은 14 다.** 15+ 전용 문법을 쓰면 테스트는 통과하고 배포에서만 죽는다.
자세한 것은 `CLAUDE.md` 「운영 PostgreSQL 은 14 다」 참고.

## 배포 — main push 자동

`.github/workflows/build.yml` 하나가 검사·빌드·이미지·배포를 순서대로 한다.

```
guard(커밋 검사) → build(테스트) → image(GHCR 푸시) → deploy(NAS)
                                    main 에서만 ──────┘
```

- **운영 설정은 저장소에 없다.** CI 가 Secret `APPLICATION_PROD_YML` 로
  `application-prod.yaml` 을 만들어 jar 에 넣는다. 그 내용에는 `${환경변수}` 자리표시자만
  있고, 리터럴 비밀값이 섞이면 워크플로가 검사해서 실패시킨다
- **비밀값은 이미지에 굽지 않는다.** 배포 시 `docker run -e` 로 주입한다 — 이미지를 받을 수
  있는 사람이 비밀을 꺼낼 수 없어야 하기 때문이다
- **배포 태그는 `latest` 가 아니라 커밋 SHA** 다. `latest` 는 다음 배포에 덮여
  "지금 서버에 뭐가 떠 있나"를 되짚을 수 없다
- 기동 확인은 `/actuator/health` 가 `UP` 이 될 때까지 최대 200초. 실패하면 컨테이너 로그를
  출력하고 워크플로를 실패시킨다

필요한 GitHub Secrets: `APPLICATION_PROD_YML`, `PALIM_DB_PASSWORD`,
`PALIM_CRYPTO_MASTER_KEY`, `SERVER_HOST`, `SERVER_USER`, `SERVER_PASSWORD`.

**롤백**은 이전 커밋 SHA 태그로 `docker run` 을 다시 하면 된다.

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
