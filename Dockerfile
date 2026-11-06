# syntax=docker/dockerfile:1

# --- Build --------------------------------------------------------------------
# Pinned to the build platform: the jar is architecture-independent, so Maven
# never needs to run under QEMU for the arm64 image.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src/ src/
RUN mvn -B -q clean package -DskipTests \
 && mv target/credit-report-*.jar target/app.jar
WORKDIR /build/target
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# --- Claude Code -------------------------------------------------------------
# Claude's notes run Claude Code in print mode on your Claude subscription, as the
# stocks app's trader does. Same pinned version. The npm package's postinstall puts
# the platform's native binary in place, so the runtime image needs no Node.
# Never install with --omit=optional or --ignore-scripts: both leave a placeholder.
FROM node:22.23.2-bookworm-slim AS claude
ARG CLAUDE_CODE_VERSION=2.1.285
RUN npm install -g --no-audit --no-fund "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
 && npm cache clean --force \
 && test "$(stat -c %s "$(readlink -f "$(command -v claude)")")" -gt 1000000 \
 && cp -rL /usr/local/lib/node_modules/@anthropic-ai/claude-code /opt/claude-code

# --- Runtime ------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

LABEL org.opencontainers.image.title="Finance" \
      org.opencontainers.image.description="Self-hosted personal finance: accounts, cash flow Sankey, targets and goals" \
      org.opencontainers.image.source="https://github.com/rjgehan/CreditReport"

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build /build/target/extracted/dependencies/ ./
COPY --from=build /build/target/extracted/spring-boot-loader/ ./
COPY --from=build /build/target/extracted/snapshot-dependencies/ ./
COPY --from=build /build/target/extracted/application/ ./
COPY --from=claude /opt/claude-code /opt/claude-code
RUN ln -s /opt/claude-code/bin/claude.exe /usr/local/bin/claude

# finance.db, the generated encryption key and the Teller certificate live here.
RUN mkdir -p /data && chown -R app:app /data /app
VOLUME ["/data"]
ENV APP_DATA_DIR=/data

USER app
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
