# ── Build stage ──────────────────────────────────────────────────────────────
# Multi-module Gradle build (build-logic composite + feature modules). Only :app is bootable and
# bundles the module jars as nested jars in its bootJar.
# Pinned to $BUILDPLATFORM: the jar is architecture-independent, so a multi-arch build (amd64 + arm64)
# runs Gradle natively once instead of under QEMU emulation. Only the runtime stage follows the target.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Wrapper + root build scripts + the composite build-logic first (best layer caching).
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY build-logic/ build-logic/
RUN chmod +x gradlew

# Feature modules + the bootable app module.
COPY common/ common/
COPY user/ user/
COPY auth/ auth/
COPY room/ room/
COPY chat/ chat/
COPY websocket/ websocket/
COPY ai/ ai/
COPY app/ app/

# Only :app produces the runnable bootJar. Skip tests in the image build (CI already gates them).
RUN ./gradlew :app:bootJar --no-daemon -x test

# ── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /build/app/build/libs/*.jar app.jar

# Heap sized as a percentage of the CONTAINER memory limit so it adapts to the host:
# Render free = 512MB (→ ~330MB heap), and larger VMs get proportionally more. Override JAVA_OPTS
# via env (e.g. Render dashboard / render.yaml) to tune per environment.
ENV JAVA_OPTS="-XX:+UseG1GC \
  -XX:MaxRAMPercentage=65.0 \
  -XX:MaxMetaspaceSize=160m \
  -Djava.security.egd=file:/dev/./urandom \
  -Dspring.jmx.enabled=false"

# Render injects PORT; the app reads ${PORT:8080}. EXPOSE is documentation only.
EXPOSE 8080

CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
