# 빌드는 CI(GitHub Actions)에서 수행하고, 이 이미지는 산출된 jar만 실행한다.
FROM eclipse-temurin:25-jre

WORKDIR /app

# py 스크립트 실행 환경 (02-ARCHITECTURE — Java 가 ProcessBuilder 로 호출한다).
# 이것이 없으면 엑셀 파싱·자막 수집이 운영에서 조용히 실패한다.
#
# 엑셀 파싱에는 openpyxl 만 쓴다. pandas 는 수십 MB 인데 우리가 쓰는 기능은
# "헤더를 읽고 각 행을 문자열로" 뿐이라 이미지만 무거워진다.
# pip 대신 apt 패키지를 쓰는 이유 — Debian 은 EXTERNALLY-MANAGED 라 pip 설치에
# --break-system-packages 가 필요하고, 그렇게 넣은 패키지는 apt 와 어긋난다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-openpyxl \
    && rm -rf /var/lib/apt/lists/*

# 루트 권한으로 실행하지 않는다
RUN groupadd --system palim && useradd --system --gid palim palim

COPY palim-app/build/libs/*.jar app.jar

# 스크립트를 이미지에 넣는다. jar 안에 넣지 않는 이유는 ProcessBuilder 가 파일 경로로
# 실행하기 때문이다 — classpath 리소스는 경로가 없다.
COPY scripts/ /app/scripts/

# 로그 디렉터리를 미리 만들어 실행 사용자에게 넘긴다.
#
# 호스트 디렉터리를 여기 연결하는 것이 운영 구성이지만, 연결이 없거나 권한이 맞지 않아도
# 앱은 떠야 한다 — 로그는 부가 기능이지 서비스의 생명이 아니다. 실제로 이 디렉터리에 쓸 수
# 없어 기동 자체가 막히고 서비스가 내려간 적이 있다.
RUN mkdir -p /mnt/palim/logs && chown -R palim:palim /mnt/palim

USER palim

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
