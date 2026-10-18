# vibefix-app: the Spring Boot application, and (under the profile sandbox) the sandbox worker (AD-32).
# Base images are pinned by tag and digest. Build context: backend/.

# The jar is the same for every platform, so it is built once on the build machine's platform.
FROM --platform=$BUILDPLATFORM eclipse-temurin:26.0.2_10-jdk-noble@sha256:abe71c9b7140affd9a80d6ed313c428e3393d4d18c235823b1745670d4702053 AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package \
    && cp target/vibefix-app-*.jar /workspace/vibefix-app.jar

FROM eclipse-temurin:26.0.2_10-jre-noble@sha256:0140636ef128fa014041fa12dae3a9de53b6a6c6f055271f4e6a7417ea34012c
# Conventions, Time: containers run in UTC. The application never runs as root.
ENV TZ=UTC
RUN groupadd --system --gid 10001 vibefix \
    && useradd --system --uid 10001 --gid vibefix --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin vibefix
WORKDIR /app
COPY --from=build --chown=root:root --chmod=0444 /workspace/vibefix-app.jar /app/vibefix-app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/vibefix-app.jar"]
