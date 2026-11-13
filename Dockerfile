# syntax=docker/dockerfile:1

# ---- build stage: compile with the Maven wrapper on JDK 25 ----
# Runs on the build machine's own platform even for a multi-platform build: the jar is the same everywhere,
# so only the runtime stage below is built per platform (no Maven under arm64 emulation).
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
# unzip: without it mvnw fetches the .tar.gz but verifies the .zip checksum
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw \
 && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

# ---- runtime stage: JRE only ----
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /build/target/quarkus-app/lib/ lib/
COPY --from=build /build/target/quarkus-app/*.jar ./
COPY --from=build /build/target/quarkus-app/app/ app/
COPY --from=build /build/target/quarkus-app/quarkus/ quarkus/
# Default data so the published image runs on its own; compose mounts ./data over it.
COPY data/ /data/
RUN addgroup -S app && adduser -S app -G app
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=10 \
  CMD wget -qO- http://localhost:8080/q/health/ready >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
