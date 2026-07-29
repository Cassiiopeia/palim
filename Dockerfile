# 빌드는 CI(GitHub Actions)에서 수행하고, 이 이미지는 산출된 jar만 실행한다.
FROM eclipse-temurin:25-jre

WORKDIR /app

# 루트 권한으로 실행하지 않는다
RUN groupadd --system palim && useradd --system --gid palim palim

COPY build/libs/*.jar app.jar

USER palim

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
