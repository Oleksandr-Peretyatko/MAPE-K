# Copy the Docker CLI binary and its Compose/Buildx plugins from the official docker image.
FROM docker:27-cli AS docker-cli

FROM eclipse-temurin:17-jre-jammy

# Install git (Execute phase) and curl (used by the Claude Code installer below).
RUN apt-get update && \
    apt-get install -y --no-install-recommends git curl && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Node.js 20 and the Claude Code CLI (used by the COMMAND mutator mode).
# The claude binary lands at /usr/local/bin/claude.
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    npm install -g @anthropic-ai/claude-code && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Copy the Docker CLI binary AND the Compose V2 plugin so `docker compose` works.
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
RUN mkdir -p /usr/local/libexec/docker/cli-plugins
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-compose \
     /usr/local/libexec/docker/cli-plugins/docker-compose

# Buildx too, or `docker build` silently falls back to the legacy builder. The daemon (29.x) keeps
# images in the containerd store, which the legacy builder cannot read cached layers out of:
# `--cache-from quizzes:latest` dies with "failed to read diff archive: InvalidArgument" partway
# through the multi-stage build, after Maven has already succeeded.
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-buildx \
     /usr/local/libexec/docker/cli-plugins/docker-buildx

# Functional test gate for the Claude mutator's last step. In the image, not the project tree, so it
# exists whatever branch the Execute phase has checked out.
COPY --chmod=755 docker/test-gate.sh /usr/local/bin/test-gate

WORKDIR /app

# Copy the fat JAR built by spring-boot-maven-plugin
COPY target/rt-app-1.0-SNAPSHOT.jar app.jar

EXPOSE 8090

ENTRYPOINT ["java", "-XX:InitialRAMPercentage=50.0", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
