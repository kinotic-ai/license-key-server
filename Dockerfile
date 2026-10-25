# The license key server image: the Vue app built with pnpm, the server built with Gradle on JDK 25, run on a JRE.
# The frontend stage needs @kinotic-ai/frontend-common and @kinotic-ai/core from npm.

FROM node:22-alpine AS frontend
RUN corepack enable && corepack prepare pnpm@10 --activate
WORKDIR /build/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml* ./
# the lockfile is committed once @kinotic-ai/frontend-common is on npm; until then the install resolves fresh
RUN if [ -f pnpm-lock.yaml ]; then pnpm install --frozen-lockfile; else pnpm install; fi
COPY frontend/ ./
RUN pnpm build

FROM eclipse-temurin:25-jdk AS server
WORKDIR /build
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
COPY --from=frontend /build/frontend/dist ./frontend/dist
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre
# Ignite 2.18 reflects into these JDK packages; Netty needs direct memory for the WebSocket frames
ENV JAVA_TOOL_OPTIONS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED -XX:MaxDirectMemorySize=256m -XX:+DisableExplicitGC"
ENV KINOTIC_IGNITE_WORKDIRECTORY=/var/lib/license-key-server/ignite
RUN useradd --system --create-home --home-dir /var/lib/license-key-server licenses \
    && mkdir -p /var/lib/license-key-server/ignite && chown -R licenses:licenses /var/lib/license-key-server
WORKDIR /opt/license-key-server
COPY --from=server /build/build/libs/license-key-server-*.jar license-key-server.jar
USER licenses
EXPOSE 58503
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD curl -fsS http://localhost:58503/health || exit 1
ENTRYPOINT ["java", "-jar", "license-key-server.jar"]
