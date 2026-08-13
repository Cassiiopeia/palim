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

> 호스트명·SSH 포트·운영 도메인·서버 계정명은 **여기 적지 않는다**(금지사항 1). 실제 값은
> GitHub Secret(`SERVER_HOST`·`SERVER_USER`·`SERVER_PASSWORD`)과 로컬 config 에만 있다.

| 항목 | 값 |
|---|---|
| 호스트 | 관리자 소유 NAS 1대 (컨테이너 런타임 + 역방향 프록시 내장) |
| 컨테이너 | `palim` — `ghcr.io/cassiiopeia/palim:<커밋 SHA>` |
| 포트 | 컨테이너 `8080` 을 호스트 포트 하나에 매핑 |
| DB | **NAS 공용 PostgreSQL 14** 의 `palim` 데이터베이스 |
| 네트워크 | DB 컨테이너와 같은 도커 네트워크에 붙여 컨테이너 이름으로 접속 |
| 외부 노출 | NAS 역방향 프록시 → HTTPS (인증서는 NAS 가 발급·갱신) |

**DB 는 여러 프로젝트가 공유한다.** 커넥션 풀 상한을 10 으로 묶어 남의 서비스가 커넥션을
못 얻는 일을 막는다.

계정은 NAS 공용 관리 계정을 그대로 쓴다. 이 NAS 는 관리자 1인이 테스트 용도로 운영하고 올라간
프로젝트가 전부 같은 사람 것이라, 프로젝트마다 계정을 나누면 관리 비용만 늘고 얻는 게 없다.
**여러 사람이 쓰는 환경으로 옮길 때는 이 판단을 다시 해야 한다** — 공용 계정은 슈퍼유저라
그 자격증명 하나로 인스턴스의 모든 DB 에 접근된다.

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

### 필요한 GitHub Secrets

배포 대상을 지목하는 값은 **하나도 파일에 두지 않는다.** 다른 환경에 올리려면 아래 Secret 만
자기 값으로 채우면 되고, 워크플로는 고치지 않아도 된다.

| Secret | 용도 |
|---|---|
| `APPLICATION_PROD_YML` | **운영 설정 전부** — DB 접속·마스터키·관리자 초기 비밀번호 |
| `SERVER_HOST` · `SERVER_USER` · `SERVER_PASSWORD` · `SERVER_SSH_PORT` | 배포 대상 서버 접속 |
| `DEPLOY_PORT` | 컨테이너 `8080` 을 붙일 호스트 포트 |
| `DEPLOY_NETWORK` | 컨테이너를 붙일 도커 네트워크 (DB 와 같은 네트워크) |

상세는 `docs/10-DEPLOYMENT.md` 를 본다 — 설정이 이미지에 들어간다는 점과 그 의미를 함께 적었다.

**롤백**은 이전 커밋 SHA 태그로 `docker run` 을 다시 하면 된다.

### 다른 환경에 올릴 때

1. 대상 서버에 컨테이너 런타임과 PostgreSQL **14 이상**을 준비하고 빈 데이터베이스를 만든다
2. 앱 컨테이너가 DB 에 컨테이너 이름으로 접근할 수 있도록 같은 도커 네트워크에 둔다
3. 위 Secret 을 채운다
4. `main` 에 push 하면 배포된다

`guard` 잡이 커밋 메시지의 AI 흔적, 사내·발주사 자료 추적, **인프라 식별정보 유출**을 검사해
위반 시 빌드를 실패시킨다. 규칙 문서만으로는 언젠가 누군가 값을 직접 적는다.

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
