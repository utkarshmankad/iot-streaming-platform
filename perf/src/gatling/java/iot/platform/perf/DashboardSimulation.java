package iot.platform.perf;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Simulation;
import java.time.Duration;

public class DashboardSimulation extends Simulation {
  public DashboardSimulation() {
    var baseUrl = System.getProperty("perf.baseUrl", "http://127.0.0.1:18080");
    var dashboard =
        scenario("Dashboard shell")
            .exec(http("Dashboard").get("/").check(status().is(200)))
            .exec(http("Readiness").get("/healthz").check(status().is(200)));
    setUp(dashboard.injectOpen(constantUsersPerSec(10).during(Duration.ofSeconds(10))))
        .protocols(http.baseUrl(baseUrl))
        .assertions(
            global().successfulRequests().percent().gte(99.0),
            global().responseTime().percentile(95).lte(500));
  }
}
