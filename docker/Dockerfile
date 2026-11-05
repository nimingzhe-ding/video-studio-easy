FROM node:22-bookworm-slim AS client
WORKDIR /build/client
COPY client/package.json client/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY client/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS server
WORKDIR /build
COPY server/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY server/src ./src
RUN mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng fonts-noto-cjk curl ca-certificates tini \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 10001 studio && useradd -u 10001 -g studio -m studio \
    && mkdir -p /app /work && chown -R studio:studio /app /work
WORKDIR /app
COPY --from=server --chown=studio:studio /build/target/video-studio-easy-server-*.jar /app/app.jar
COPY --from=client --chown=studio:studio /build/client/dist /app/client/dist
COPY --chown=studio:studio docker/entrypoint.sh /app/entrypoint.sh
RUN chmod 755 /app/entrypoint.sh
ENV WORK_ROOT=/work SERVER_ADDRESS=0.0.0.0 SERVER_PORT=9090 JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"
USER studio
EXPOSE 9090
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s CMD curl -fsS http://localhost:9090/actuator/health/readiness || exit 1
ENTRYPOINT ["/usr/bin/tini","--","/app/entrypoint.sh"]
