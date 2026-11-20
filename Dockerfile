FROM eclipse-temurin:25.0.5_8-jdk-noble AS build
WORKDIR /workspace
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY backend/ backend/
COPY gateway/ gateway/
COPY simulator/ simulator/
COPY perf/build.gradle.kts perf/
COPY contracts/proto/ contracts/proto/
RUN ./gradlew :backend:bootJar :gateway:bootJar :simulator:bootJar --no-daemon

FROM eclipse-temurin:25.0.5_8-jre-alpine
WORKDIR /app
RUN mkdir -p /var/lib/iot && chown 10001:10001 /var/lib/iot
ARG SERVICE
COPY --from=build /workspace/${SERVICE}/build/libs/application.jar application.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
