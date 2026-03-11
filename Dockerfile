# syntax=docker/dockerfile:1

FROM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY src src

RUN ./gradlew --no-daemon shadowJar

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /workspace/build/libs/cotor-*-all.jar /app/cotor.jar

ENTRYPOINT ["java", "-jar", "/app/cotor.jar"]
CMD ["version"]
