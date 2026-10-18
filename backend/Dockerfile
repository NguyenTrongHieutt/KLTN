# vibefix-app: the Spring Boot application, and (under the profile sandbox) the sandbox worker (AD-32).
# Base images are pinned by tag and digest. Build context: backend/.

# The jar is the same for every platform, so it is built once on the build machine's platform.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package \
    && cp target/vibefix-app-*.jar /workspace/vibefix-app.jar

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5
# Conventions, Time: containers run in UTC. The application never runs as root.
ENV TZ=UTC
RUN groupadd --system --gid 10001 vibefix \
    && useradd --system --uid 10001 --gid vibefix --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin vibefix
WORKDIR /app
COPY --from=build --chown=root:root --chmod=0444 /workspace/vibefix-app.jar /app/vibefix-app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/vibefix-app.jar"]
