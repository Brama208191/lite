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
RUN mvn -q -B -pl runtime -am dependency:go-offline
COPY runtime/src runtime/src
COPY --from=web /web/dist runtime/src/main/resources/static
RUN mvn -q -B -pl runtime -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 keel
COPY --from=build /src/runtime/target/keel-runtime.jar /app/keel-runtime.jar
USER keel
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/keel-runtime.jar"]
