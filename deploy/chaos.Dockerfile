FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY runtime/pom.xml runtime/
COPY receiver/pom.xml receiver/
COPY chaos/pom.xml chaos/
RUN mvn -q -B -pl runtime,chaos -am dependency:go-offline
COPY runtime/src runtime/src
COPY chaos/src chaos/src
RUN mvn -q -B -pl runtime,chaos -am package -DskipTests

FROM eclipse-temurin:21-jre
COPY --from=build /src/runtime/target/keel-runtime.jar /app/keel-runtime.jar
COPY --from=build /src/chaos/target/keel-chaos.jar /app/keel-chaos.jar
ENV RUNTIME_JAR=/app/keel-runtime.jar OUT_DIR=/out
ENTRYPOINT ["java", "-jar", "/app/keel-chaos.jar"]
