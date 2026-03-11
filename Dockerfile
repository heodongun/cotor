# syntax=docker/dockerfile:1

FROM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY src src

RUN ./gradlew --no-daemon shadowJar

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app

COPY --from=build /workspace/build/libs/cotor-*-all.jar /app/cotor.jar
RUN useradd --create-home --uid 10001 cotor \
    && chown -R cotor:cotor /app

USER cotor

EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl --fail http://127.0.0.1:8787/ready || exit 1

ENTRYPOINT ["java", "-jar", "/app/cotor.jar"]
CMD ["app-server", "--host", "0.0.0.0", "--port", "8787"]
