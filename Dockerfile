FROM eclipse-temurin:26.0.2.1_1-jdk-noble AS build
WORKDIR /workspace
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY backend/ backend/
COPY gateway/ gateway/
COPY simulator/ simulator/
COPY perf/build.gradle.kts perf/
COPY contracts/ contracts/
RUN ./gradlew :backend:bootJar :gateway:bootJar :simulator:bootJar --no-daemon

FROM eclipse-temurin:27.0.1.0-jre-alpine
WORKDIR /app
# Install the fix for CVE-2026-109908 in the inherited font library.
RUN apk add --no-cache 'libfontenc>=1.1.10-r0' \
    && mkdir -p /var/lib/iot && chown 10001:10001 /var/lib/iot
ARG SERVICE
COPY --from=build /workspace/${SERVICE}/build/libs/application.jar application.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
