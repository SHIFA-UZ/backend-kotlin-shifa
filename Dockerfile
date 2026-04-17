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

ENTRYPOINT ["java", \
  "-Xmx512m", "-Xms256m", \
  "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", \
  "-XX:+UseStringDeduplication", \
  "-jar", "build/libs/app.jar"]
