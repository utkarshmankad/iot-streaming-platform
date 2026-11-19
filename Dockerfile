FROM eclipse-temurin:25.0.5_8-jdk-noble AS build
WORKDIR /workspace
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY backend/ backend/
COPY gateway/ gateway/
COPY simulator/ simulator/
COPY perf/build.gradle.kts perf/
ARG SERVICE
RUN ./gradlew ":${SERVICE}:bootJar" --no-daemon && \
    cp "${SERVICE}/build/libs/application.jar" /tmp/application.jar

FROM eclipse-temurin:25.0.5_8-jre-alpine
WORKDIR /app
RUN mkdir -p /var/lib/iot && chown 10001:10001 /var/lib/iot
COPY --from=build /tmp/application.jar application.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
