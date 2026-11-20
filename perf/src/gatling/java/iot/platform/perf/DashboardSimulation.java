package iot.platform.perf;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.listFeeder;
import static io.gatling.javaapi.core.CoreDsl.rendezVous;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Simulation;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class DashboardSimulation extends Simulation {
  private static final int READERS = 5;
  private static final int ROUNDS = 3;

  public DashboardSimulation() {
    var baseUrl = System.getProperty("perf.baseUrl", "http://127.0.0.1:18080");
    String firstPassword = password("IOT_PERF_A_PASSWORD");
    String secondPassword = password("IOT_PERF_B_PASSWORD");
    var dashboard =
        scenario("Dashboard shell")
            .exec(http("Dashboard").get("/").check(status().is(200)))
            .exec(http("Readiness").get("/healthz").check(status().is(200)));
    var readers =
        scenario("Concurrent tenant trend readers")
            .feed(
                listFeeder(
                        List.of(
                            Map.of("username", "operator-a"),
                            Map.of("username", "operator-a"),
                            Map.of("username", "operator-a"),
                            Map.of("username", "operator-b"),
                            Map.of("username", "operator-b")))
                    .queue())
            .exec(
                http("CSRF")
                    .get("/api/v1/session/csrf")
                    .check(
                        status().is(200),
                        jsonPath("$.headerName").is("X-CSRF-TOKEN"),
                        jsonPath("$.token").saveAs("csrf")))
            .exec(
                http("Sign in")
                    .post("/api/v1/session")
                    .asJson()
                    .header("X-CSRF-TOKEN", "#{csrf}")
                    .body(
                        StringBody(
                            session -> {
                              String user = session.getString("username");
                              String password =
                                  "operator-a".equals(user) ? firstPassword : secondPassword;
                              return """
                                  {"username":"%s","password":"%s"}
                                  """
                                  .formatted(user, password);
                            }))
                    .check(status().is(200), jsonPath("$.username").isEL("#{username}")))
            .exec(
                http("Sensor registry")
                    .get("/api/v1/sensors?limit=10")
                    .check(
                        status().is(200),
                        jsonPath("$.items[*].sensorId").findAll().saveAs("sensors")))
            .repeat(ROUNDS)
            .on(
                trends("15m", Duration.ofMinutes(15), Duration.ofHours(6), 1),
                trends("1h", Duration.ofHours(1), Duration.ofDays(2), 3),
                trends("1d", Duration.ofDays(1), Duration.ofDays(7), 6),
                trends("1w", Duration.ofDays(7), Duration.ofDays(28), 10));
    setUp(
            dashboard.injectOpen(constantUsersPerSec(10).during(Duration.ofSeconds(10))),
            readers.injectOpen(atOnceUsers(READERS)))
        .maxDuration(Duration.ofMinutes(2))
        .protocols(http.baseUrl(baseUrl))
        .assertions(
            global().successfulRequests().percent().gte(99.0),
            global().responseTime().percentile(95).lte(500),
            details("Trend 15m").responseTime().percentile(95).lte(500),
            details("Trend 1h").responseTime().percentile(95).lte(500),
            details("Trend 1d").responseTime().percentile(95).lte(500),
            details("Trend 1w").responseTime().percentile(95).lte(500),
            details("Trend 15m").successfulRequests().count().is((long) READERS * ROUNDS),
            details("Trend 1h").successfulRequests().count().is((long) READERS * ROUNDS),
            details("Trend 1d").successfulRequests().count().is((long) READERS * ROUNDS),
            details("Trend 1w").successfulRequests().count().is((long) READERS * ROUNDS));
  }

  @Override
  public void before() {
    snapshotCommits("before");
  }

  @Override
  public void after() {
    snapshotCommits("after");
  }

  private static ChainBuilder trends(
      String resolution, Duration width, Duration window, int sensors) {
    return rendezVous(READERS)
        .exec(
            http("Trend " + resolution)
                .get(
                    session -> {
                      long seconds = width.toSeconds();
                      long origin = "1w".equals(resolution) ? Duration.ofDays(4).toSeconds() : 0;
                      long now = Instant.now().getEpochSecond();
                      long boundary = Math.floorDiv(now - origin, seconds) * seconds + origin;
                      var end = Instant.ofEpochSecond(boundary + seconds);
                      var from = end.minus(window);
                      List<String> ids = session.getList("sensors");
                      var path =
                          new StringBuilder("/api/v1/trends?bucket=")
                              .append(resolution)
                              .append("&from=")
                              .append(from)
                              .append("&to=")
                              .append(end);
                      for (String id : ids.subList(0, sensors)) {
                        path.append("&sensorId=").append(id);
                      }
                      return path.toString();
                    })
                .check(
                    status().is(200),
                    jsonPath("$.bucket").is(resolution),
                    jsonPath("$.series[*].sensorId").count().is(sensors)))
        .pause(Duration.ofSeconds(2));
  }

  private static void snapshotCommits(String moment) {
    var output = Path.of(System.getenv("IOT_PERF_SNAPSHOTS")).resolve(moment);
    try {
      var process =
          new ProcessBuilder("/bin/bash", System.getenv("IOT_PERF_SCRIPT"), "snapshot")
              .redirectOutput(output.toFile())
              .redirectError(ProcessBuilder.Redirect.INHERIT)
              .start();
      if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("Commit metric snapshot timed out");
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("Commit metric snapshot failed");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Commit metric snapshot interrupted", interrupted);
    } catch (IOException failed) {
      throw new IllegalStateException("Commit metric snapshot unavailable", failed);
    }
  }

  private static String password(String name) {
    String value = System.getenv(name);
    if (value == null || !value.matches("[a-f0-9]{48}")) {
      throw new IllegalStateException("Start the test stack and run scripts/ci/performance.sh");
    }
    return value;
  }
}
