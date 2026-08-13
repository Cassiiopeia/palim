# 10. 배포

`main` 에 push 하면 검사·빌드·이미지·배포가 자동으로 이어진다. 손으로 하는 단계는 없다.

> 운영 환경의 구체적 값(호스트·포트·도메인·계정)은 이 문서에 적지 않는다. 저장소가 공개이고
> 남이 가져다 쓸 수 있기 때문이다. 실제 값은 GitHub Secret 에만 있다.

## 1. 전체 그림

```
develop 에 push   →  guard + build            (테스트만, 배포 안 함)
main 에 push      →  guard + build + image + deploy
```

```
main push
   │
   ├─ guard    커밋 메시지 AI 흔적 / 사내·발주사 자료 / 인프라 식별정보 검사
   │
   ├─ build    ./gradlew build  (전체 테스트)
   │
   ├─ image    ① Secret → application-prod.yaml 생성
   │           ② bootJar
   │           ③ SBOM 생성 (의존성 목록, 90일 보관)
   │           ④ ghcr.io 로그인
   │           ⑤ 이미지 빌드 → 태그 2개로 push
   │
   └─ deploy   ⑥ SSH 접속
               ⑦ 커밋 SHA 태그로 pull
               ⑧ 기존 컨테이너 제거 → docker run
               ⑨ /actuator/health 가 UP 될 때까지 최대 200초 확인
```

`image` 와 `deploy` 는 `if: github.ref == 'refs/heads/main'` 이라 다른 브랜치에서는 건너뛴다.

## 2. 이미지 저장소 — GHCR

컨테이너 이미지는 **GHCR**(GitHub Container Registry)에 올린다. DockerHub 와 하는 일은 같고
주소 앞에 `ghcr.io` 가 붙는다.

| | DockerHub | GHCR |
|---|---|---|
| private 저장소 | 무료 계정 1개 제한 | **무제한 무료** |
| CI 인증 | 계정·토큰을 발급해 Secret 등록 | **자동 제공, 등록 불필요** |
| 권한 | 별도 계정 관리 | GitHub 저장소 권한을 따름 |

인증에 쓰는 `GITHUB_TOKEN` 은 **등록하지 않는다.** GitHub Actions 가 실행마다 발급하고
끝나면 만료시킨다. 그래서 이 프로젝트 Secret 목록에 컨테이너 저장소 자격증명이 없다.

```yaml
registry: ghcr.io
username: ${{ github.actor }}            # 자동
password: ${{ secrets.GITHUB_TOKEN }}    # 자동
```

## 3. 태그 규칙 — 배포에는 latest 를 쓰지 않는다

푸시할 때 태그를 두 개 단다.

| 태그 | 성격 | 용도 |
|---|---|---|
| `<커밋 SHA>` | **불변** | **배포는 이것만 쓴다** |
| `latest` | 다음 배포에 덮인다 | 사람이 손으로 받아볼 때 |

`latest` 로 배포하면 "지금 서버에 떠 있는 게 어느 커밋인지" 나중에 알 수 없다. SHA 태그를
쓰면 `docker ps` 만으로 정확히 어느 코드인지 나오고, **롤백이 이전 SHA 로 다시 띄우는 것**으로
끝난다 — 다시 빌드하지 않는다.

## 4. 비밀값을 다루는 방식

이미지가 공개이므로 **비밀값을 이미지에 넣지 않는다.** 두 단계로 나눈다.

```
① 빌드 시   Secret APPLICATION_PROD_YML → application-prod.yaml 파일 → jar → 이미지
             이 파일에는 ${환경변수} 자리표시자만 있다

② 실행 시   docker run -e DB_PASSWORD=... 로 실제 값 주입
             ①의 ${DB_PASSWORD} 자리가 이때 채워진다
```

`image` 잡이 ①의 내용에 **리터럴 비밀값이 섞였는지 검사해 빌드를 실패시킨다.** 규칙 문서만
두면 언젠가 누군가 값을 직접 적는다.

이 방식이 막아주는 것과 못 막는 것을 분명히 해 둔다.

| | 막는다 | 못 막는다 |
|---|---|---|
| 이미지에 값 기입 | — | 이미지를 받는 누구나 |
| **현재 방식(`-e` 주입)** | 이미지 유출 | 서버 접근자가 `docker inspect` 로 조회 |
| 파일 마운트(600) | 위 + inspect | 서버에서 파일 읽기 |
| 시크릿 관리자 | 위 + 디스크 평문 | — |

지금은 서버 운영자와 비밀 접근자가 같은 사람이라 현재 방식으로 충분하다. **둘이 분리되는
조직으로 가면 그때 단계를 올린다.**

## 5. 필요한 GitHub Secrets

| Secret | 용도 |
|---|---|
| `APPLICATION_PROD_YML` | 운영 설정 본문 (자리표시자만) |
| `SERVER_HOST` · `SERVER_USER` · `SERVER_PASSWORD` · `SERVER_SSH_PORT` | 배포 서버 접속 |
| `DEPLOY_PORT` | 컨테이너 `8080` 을 붙일 호스트 포트 |
| `DEPLOY_NETWORK` | 컨테이너를 붙일 도커 네트워크 (DB 와 같은 네트워크) |
| `PALIM_DB_HOST` · `PALIM_DB_NAME` · `PALIM_DB_USER` · `PALIM_DB_PASSWORD` | DB 접속 |
| `PALIM_CRYPTO_MASTER_KEY` | 인증정보 암호화 마스터키 |
| `PALIM_ADMIN_PASSWORD` | 관리자 초기 비밀번호 |

**`PALIM_ADMIN_PASSWORD` 를 비우면 안 된다.** 비면 코드의 기본값으로 관리자 계정이 만들어지는데,
그 값은 공개 문서에도 적혀 있다. "최초 로그인 시 변경 강제"가 있지만 그건 방어가 아니다 —
공격자도 기본값을 알고 있으므로 **먼저 로그인해서 자기 비밀번호로 바꾸면 그만**이고, 그러면
정당한 소유자가 잠긴다.

## 6. 다른 환경에 올리기

배포 대상을 지목하는 값은 파일에 하나도 없다. **Secret 만 채우면 워크플로는 고치지 않는다.**

1. 대상 서버에 컨테이너 런타임과 **PostgreSQL 14 이상**을 준비하고 빈 데이터베이스를 만든다
2. 앱이 DB 에 컨테이너 이름으로 붙도록 같은 도커 네트워크에 둔다
3. 위 Secret 을 채운다
4. `main` 에 push 한다

`deploy` 스텝은 컨테이너 런타임 경로를 후보 목록에서 찾으므로 특정 배포판을 가정하지 않는다.

## 7. 배포가 실패하면

`deploy` 는 `/actuator/health` 가 `UP` 이 될 때까지 최대 200초 기다린다. 안 뜨면 **컨테이너
로그를 출력하고 워크플로를 실패시킨다** — 죽은 채로 성공 처리되지 않는다.

| 증상 | 흔한 원인 |
|---|---|
| Flyway 문법 오류로 기동 실패 | 마이그레이션에 PostgreSQL 15+ 문법 사용 (`CLAUDE.md` 참고) |
| DB 연결 실패 | `DEPLOY_NETWORK` 가 DB 컨테이너와 다름 |
| 이미지 pull 실패 | 패키지가 private 인데 토큰 권한 부족 (`packages: read`) |
| 헬스체크 타임아웃 | 마이그레이션이 오래 걸림 — 로그로 진행 확인 |

**롤백**은 이전 커밋 SHA 태그로 `docker run` 을 다시 하면 된다.

## 8. 배포하지 않는 것

- **`develop` push** — 테스트만 돈다. 배포하려면 `develop → main` PR 을 머지한다
- **수동 실행** — `workflow_dispatch` 는 아직 없다. 필요해지면 추가한다
