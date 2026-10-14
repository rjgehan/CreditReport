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

# finance.db, the generated encryption key and the Teller certificate live here.
RUN mkdir -p /data && chown -R app:app /data /app
VOLUME ["/data"]
ENV APP_DATA_DIR=/data

USER app
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
