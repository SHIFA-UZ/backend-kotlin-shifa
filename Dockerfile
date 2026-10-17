# Build stage
FROM gradle:8.5-jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
# gradle.properties sets -Xmx2g for Gradle and the Kotlin compiler daemon.
# -Xmx512m on this line overrode that and died with GC overhead while compiling
# PatientFormDto's large default constructor.
RUN gradle clean build -x test --no-daemon

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
# Target ~2GB+ container RAM total (heap + metaspace + native). On Cloud Run deploy with --memory=2Gi:
# 1024m heap + 320m metaspace + JVM native (~200-350Mi) + RAM-backed /tmp (voice uploads write up to 25MB
# there) needs ~1.7Gi peak. Railway overrides this via startCommand with -Xmx1536m.
# Bound thread-stack memory with SERVER_TOMCAT_THREADS_MAX (env) — application-prod.yml defaults to 300
# threads (~1MB stack each); 80 matches Cloud Run's per-instance concurrency.
# Only shrink to -Xmx768m/-XX:MaxMetaspaceSize=256m (for a 1.5Gi container) after confirming real memory
# headroom in Cloud Run metrics — see MIGRATION_GCP_COST.md Tier B.
ENTRYPOINT ["java", \
  "-Xmx1024m", "-Xms384m", \
  "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", \
  "-XX:+UseStringDeduplication", "-XX:MaxMetaspaceSize=320m", \
  "-jar", "build/libs/app.jar"]
