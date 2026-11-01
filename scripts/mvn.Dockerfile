FROM maven:3.9-eclipse-temurin-21
COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker
