# syntax=docker/dockerfile:1
# Stage 1: React/TypeScript storefront. Bun installs and builds the Vite app
# straight into the Manager's /web resources (index.html + hashed assets/).
FROM oven/bun:1 AS frontend
WORKDIR /workspace
COPY web/package.json web/bun.lock web/
COPY web/index.html web/vite.config.ts web/tsconfig.json web/vitest.config.ts web/playwright.config.ts web/
COPY web/src/ web/src/
COPY web/e2e/ web/e2e/
COPY stash-manager-server/src/main/resources/web/ stash-manager-server/src/main/resources/web/
RUN cd web && bun install --frozen-lockfile && bun run clean && bun run build

FROM eclipse-temurin:21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438 AS build
WORKDIR /workspace
COPY gradlew ./
COPY gradle/wrapper/ gradle/wrapper/
# Route Gradle's Maven Central resolution through a public mirror during image builds.
COPY gradle/ci-maven-central-mirror.init.gradle gradle/ci-maven-central-mirror.init.gradle
COPY stash-manager-server/build.gradle.kts stash-manager-server/settings.gradle.kts stash-manager-server/
COPY common/ common/
COPY stash-manager-server/src/ stash-manager-server/src/
COPY --from=frontend /workspace/stash-manager-server/src/main/resources/web/index.html stash-manager-server/src/main/resources/web/index.html
COPY --from=frontend /workspace/stash-manager-server/src/main/resources/web/assets/ stash-manager-server/src/main/resources/web/assets/
COPY LICENSE ./
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --init-script /workspace/gradle/ci-maven-central-mirror.init.gradle -p stash-manager-server clean build

# Java 21.0.13+9 runtime; tested independently from the frozen build JDK.
FROM eclipse-temurin:21-jre-jammy@sha256:dff6eb46527bd293a3c06bbbe9e64e8c9f91f01905b5abbdad31761c03059d2c AS runtime
ARG VERSION=0.1.0-dev.10
ARG REVISION=unknown
LABEL org.opencontainers.image.title="WeSort Manager" \
      org.opencontainers.image.description="One Java Manager with built-in web/API for a shared guild stash and order system" \
      org.opencontainers.image.source="https://github.com/Crispy-Monke-eSports/WeSort" \
      org.opencontainers.image.licenses="GPL-3.0" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
RUN groupadd --gid 10001 wesort && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin wesort \
    && install -d -m 0700 -o 10001 -g 10001 /var/lib/wesort \
    && install -d -m 0755 /opt/wesort
COPY --from=build /workspace/stash-manager-server/build/libs/wesort-manager.jar /opt/wesort/wesort-manager.jar
COPY --from=build /workspace/stash-manager-server/build/sqlite-native/libsqlitejdbc.so /opt/wesort/native/libsqlitejdbc.so
COPY LICENSE /opt/wesort/LICENSE
COPY docker/entrypoint.sh /opt/wesort/entrypoint
RUN chmod 0555 /opt/wesort/entrypoint && chmod 0444 /opt/wesort/wesort-manager.jar /opt/wesort/LICENSE
WORKDIR /opt/wesort
USER 10001:10001
EXPOSE 8765
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 CMD ["/opt/wesort/entrypoint", "healthcheck"]
ENTRYPOINT ["/opt/wesort/entrypoint"]
CMD ["serve"]
