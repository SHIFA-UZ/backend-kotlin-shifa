# Build stage
FROM gradle:8.5-jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN gradle -Dorg.gradle.jvmargs=-Xmx512m clean build -x test --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create storage directory
RUN mkdir -p /tmp/storage/images

# Same path as Nixpacks + railway.toml startCommand. Railway often overrides Docker ENTRYPOINT with that
# command; if the jar only existed as ./app.jar, deploy fails with "Unable to access jarfile build/libs/app.jar".
RUN mkdir -p build/libs
COPY --from=build /app/build/libs/app.jar build/libs/app.jar

EXPOSE 8080

# JVM heap: 512Mi was tight for Hibernate + Flyway cold start → Tomcat never answered healthchecks ("unavailable").
# Sized for a ~1.5Gi container (Cloud Run): 768m heap + 256m metaspace = 1024m, leaving ~500Mi for JVM
# native (code cache, GC structures, thread stacks) plus tmpfs — note /tmp is RAM-backed on Cloud Run and
# the voice-upload endpoints write up to 25MB there.
# Cap Tomcat threads via SERVER_TOMCAT_THREADS_MAX (env) to bound thread-stack memory on small containers.
# For a 2Gi container, -Xmx1024m / MaxMetaspaceSize=320m is also fine.
ENTRYPOINT ["java", \
  "-Xmx768m", "-Xms256m", \
  "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", \
  "-XX:+UseStringDeduplication", "-XX:MaxMetaspaceSize=256m", \
  "-jar", "build/libs/app.jar"]
