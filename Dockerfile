# syntax=docker/dockerfile:1

# Build the jar once on the native builder; the JVM artifact is reused for both target platforms.
FROM --platform=$BUILDPLATFORM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY src src

RUN gradle --no-daemon shadowJar

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/build/libs/cotor-*-all.jar /app/cotor.jar

EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD ["curl", "--fail", "--silent", "http://127.0.0.1:8787/health"]

ENTRYPOINT ["java", "-jar", "/app/cotor.jar"]
CMD ["app-server", "--host", "0.0.0.0", "--port", "8787"]
