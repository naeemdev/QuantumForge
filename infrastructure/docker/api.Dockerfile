# syntax=docker/dockerfile:1.7
# QuantumForge API (control plane). Build context: repository root.
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY services/api/build.gradle.kts services/api/
COPY services/worker/build.gradle.kts services/worker/
RUN ./gradlew --no-daemon :services:api:dependencies > /dev/null
COPY services services
RUN ./gradlew --no-daemon :services:api:bootJar -x test && cp services/api/build/libs/api-*.jar /app.jar

FROM eclipse-temurin:21-jre-noble
# git is needed for Git imports; hooks and helpers are disabled by the importer.
RUN apt-get update && apt-get install -y --no-install-recommends git curl && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home /app quantumforge && mkdir -p /data/blobs && chown -R quantumforge /data
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER 10001
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 CMD curl -fsS http://localhost:8080/health/live || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
