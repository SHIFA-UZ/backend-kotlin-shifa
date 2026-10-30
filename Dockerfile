# Build stage: the Gradle wrapper (gradle/wrapper/gradle-wrapper.properties, the version CI uses), not an image's own
# Gradle. The Spring Boot 4 plugin needs Gradle 8.14+, and the gradle:8.5 image failed the build after the upgrade.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
# gradle.properties sets -Xmx2g for Gradle and the Kotlin compiler daemon.
# -Xmx512m on this line overrode that and died with GC overhead while compiling
# PatientFormDto's large default constructor.
# bootJar only: tests, detekt and the coverage floor run in CI (ci.yml). `build` here would need config/detekt
# in the image and fails without it.
RUN ./gradlew clean bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create storage directory
RUN mkdir -p /tmp/storage/images

# Same path as the Railway service start command (service settings). Railway overrides the Docker ENTRYPOINT with that
# command; if the jar only existed as ./app.jar, deploy fails with "Unable to access jarfile build/libs/app.jar".
RUN mkdir -p build/libs
COPY --from=build /app/build/libs/app.jar build/libs/app.jar

EXPOSE 8080

# JVM heap: 512Mi was tight for Hibernate + Flyway cold start → Tomcat never answered healthchecks ("unavailable").
# Target ~2GB+ container RAM total: 1024m heap + 320m metaspace + JVM native (~200-350Mi) + /tmp (voice uploads
# write up to 25MB there). This ENTRYPOINT is the image default; Railway overrides it with its start command
# (-Xmx1536m, see the service settings and docs/ARCHITECTURE_ROADMAP.md 1.13). Tomcat threads: application-prod.yml.
ENTRYPOINT ["java", \
  "-Xmx1024m", "-Xms384m", \
  "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", \
  "-XX:+UseStringDeduplication", "-XX:MaxMetaspaceSize=320m", \
  "-jar", "build/libs/app.jar"]
