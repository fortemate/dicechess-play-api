# syntax=docker/dockerfile:1

# Build stage — runs on the builder's native platform: JVM bytecode is
# architecture-independent, so only the runtime stage needs multi-arch.
# Pin the OS variant (-noble = Ubuntu 24.04): the unsuffixed tag drifted to
# Ubuntu 26.04, whose uutils multicall coreutils breaks the app launcher.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-noble AS build

ARG SBT_VERSION=2.0.6
ADD https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz /tmp/sbt.tgz
RUN tar -xzf /tmp/sbt.tgz -C /usr/local && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt

WORKDIR /build

# Resolve dependencies first for layer caching. The engine artifact comes from
# GitHub Packages, so sbt needs a read:packages token — passed as a BuildKit
# secret so it never lands in an image layer.
ARG GITHUB_ACTOR=rabestro
COPY project/ project/
COPY build.sbt ./
RUN --mount=type=secret,id=github_token \
    GITHUB_TOKEN=$(cat /run/secrets/github_token) sbt update

COPY src/main/ src/main/
RUN --mount=type=secret,id=github_token \
    GITHUB_TOKEN=$(cat /run/secrets/github_token) sbt stage && \
    mkdir -p /build/target/universal && \
    cp -a /build/target/out/jvm/*/*/universal/stage /build/target/universal/

# Runtime stage — pinned to -noble (Ubuntu 24.04, GNU coreutils). The unsuffixed
# eclipse-temurin:25-jre drifted to Ubuntu 26.04, which ships uutils as a single
# multicall coreutils binary and breaks the sbt-native-packager launcher.
FROM eclipse-temurin:25-jre-noble

LABEL org.opencontainers.image.title="dicechess-play-api"
LABEL org.opencontainers.image.description="DiceChess Play API"
LABEL org.opencontainers.image.url="https://github.com/fortemate/dicechess-play-api"
LABEL org.opencontainers.image.source="https://github.com/fortemate/dicechess-play-api"
LABEL org.opencontainers.image.vendor="Fortemate"
LABEL org.opencontainers.image.licenses="AGPL-3.0-only"
LABEL org.opencontainers.image.authors="Fortemate"
LABEL org.opencontainers.image.base.name="eclipse-temurin:25-jre-noble"

# curl: used by the compose healthcheck (GET /health) and handy for debugging.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Static UID/GID for predictable permission mapping under security policies.
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app app
WORKDIR /app
# --chown so the runtime user owns (and can execute) the app regardless of the
# build environment's umask.
COPY --from=build --chown=app:app /build/target/universal/stage /app
USER app

# Released version, injected by the deploy build-arg and read at runtime for GET /version.
# Placed after COPY so a version change only rebuilds this tiny layer, not the staged app.
ARG APP_VERSION=dev
ENV APP_VERSION=$APP_VERSION
ENV JAVA_OPTS="-Dcats.effect.warnOnNonMainThreadDetected=false --sun-misc-unsafe-memory-access=allow"

EXPOSE 8080
ENTRYPOINT ["/app/bin/dicechess-play-api"]
