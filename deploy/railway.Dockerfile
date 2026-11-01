FROM node:20-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY runtime/pom.xml runtime/
COPY receiver/pom.xml receiver/
COPY chaos/pom.xml chaos/
RUN mvn -q -B -pl runtime,receiver -am dependency:go-offline
COPY runtime/src runtime/src
COPY receiver/src receiver/src
COPY --from=web /web/dist runtime/src/main/resources/static
RUN mvn -q -B -pl runtime,receiver -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/runtime/target/keel-runtime.jar /app/keel-runtime.jar
COPY --from=build /src/receiver/target/keel-receiver.jar /app/keel-receiver.jar
COPY deploy/railway-start.sh /app/start.sh
RUN sed -i 's/\r$//' /app/start.sh && chmod 755 /app/start.sh
ENV KEEL_JAVA_OPTS="-Xmx150m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxMetaspaceSize=140m -XX:ReservedCodeCacheSize=32m" \
    RECEIVER_JAVA_OPTS="-Xmx64m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxMetaspaceSize=100m -XX:ReservedCodeCacheSize=24m" \
    KEEL_FAKE_MIN_LATENCY=1500ms \
    KEEL_FAKE_MAX_LATENCY=2500ms \
    KEEL_MAX_RUNS_PER_MINUTE=30 \
    KEEL_DB_POOL=4 \
    RECEIVER_DB_POOL=2 \
    SPRING_DATASOURCE_HIKARI_MINIMUMIDLE=1
EXPOSE 8080
CMD ["/app/start.sh"]
