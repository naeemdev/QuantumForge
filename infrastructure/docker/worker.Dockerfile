# syntax=docker/dockerfile:1.7
# QuantumForge execution worker. Build context: repository root.
#
# This image is used for the development worker (dev-process isolation): jobs
# run as child processes inside this container, which is itself unprivileged,
# has no access to the database, object storage or API secrets, and only talks
# to the API's internal port. That is a development convenience, NOT a
# hostile-code boundary. Production workers use container-gvisor or microVM
# isolation on dedicated hosts; see docs/SECURITY_MODEL.md.
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY services/api/build.gradle.kts services/api/
COPY services/worker/build.gradle.kts services/worker/
RUN ./gradlew --no-daemon :services:worker:dependencies > /dev/null
COPY services/worker services/worker
RUN ./gradlew --no-daemon :services:worker:installDist -x test

FROM node:22.22-bookworm-slim AS node

# TypeScript adapter with production dependencies only, as a plain directory.
FROM node:22.22-bookworm-slim AS analyzer
RUN corepack enable
WORKDIR /src
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY packages/language-sdk packages/language-sdk
COPY language-packs/typescript language-packs/typescript
RUN pnpm install --frozen-lockfile --filter @quantumforge/lang-typescript... \
 && pnpm --filter @quantumforge/lang-typescript deploy --prod --legacy /opt/ts-analyzer

FROM eclipse-temurin:21-jre-noble
COPY --from=node /usr/local/bin/node /usr/local/bin/node
RUN apt-get update && apt-get install -y --no-install-recommends util-linux && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10002 --home /work worker && mkdir -p /work && chown worker /work
COPY --from=build /src/services/worker/build/install/quantumforge-worker /opt/worker
COPY --from=analyzer /opt/ts-analyzer /opt/ts-analyzer
USER 10002
ENV QF_WORK_ROOT=/work QF_NODE=/usr/local/bin/node QF_TS_ANALYZER=/opt/ts-analyzer
ENTRYPOINT ["/opt/worker/bin/quantumforge-worker"]
