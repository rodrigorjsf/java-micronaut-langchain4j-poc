# syntax=docker/dockerfile:1.7
#
# Two stages: the build stage keeps the Maven repository in a BuildKit cache mount
# (so a source-only change does not re-download ~200 MB of dependencies), and the
# runtime stage ships a JRE with the fat jar only.
#
# Built with the project's own Maven wrapper rather than a maven:* base image, so
# the container build and a local `./mvnw` use byte-identical Maven 3.9.16.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Wrapper + POM first: this layer is invalidated only by a dependency change.
# go-offline is best-effort — it trips over annotation-processor paths on some
# setups, and the real `package` below re-resolves whatever it missed.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline -DskipTests || true

COPY aot-jar.properties openapi.properties ./
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package \
    && cp target/agenticchat-*.jar /build/app.jar

FROM eclipse-temurin:25-jre AS runtime

# curl is here only so HEALTHCHECK can speak HTTP; the JRE image has no shell
# builtin that can (its /bin/sh is dash, which has no /dev/tcp).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=app:app /build/app.jar /app/app.jar
USER app

EXPOSE 8080

# MaxRAMPercentage keeps the heap inside the container limit. It matters here:
# this machine exposes only ~7 GB to WSL2, shared with floci and its Valkey child
# container. The in-process ONNX embedding model needs native memory outside the
# heap, hence 65 rather than the usual 75.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"

# start-period covers JVM boot plus the ~6 s cold load of the ONNX embedding model.
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=6 \
    CMD curl -fsS http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
