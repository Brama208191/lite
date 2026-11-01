FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY runtime/pom.xml runtime/
COPY receiver/pom.xml receiver/
COPY chaos/pom.xml chaos/
RUN mvn -q -B -pl receiver -am dependency:go-offline
COPY receiver/src receiver/src
RUN mvn -q -B -pl receiver -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 keel
COPY --from=build /src/receiver/target/keel-receiver.jar /app/keel-receiver.jar
USER keel
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 8090
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/keel-receiver.jar"]
