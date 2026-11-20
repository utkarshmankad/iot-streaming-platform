package iot.platform.backend.browser;

import iot.platform.backend.analytics.TrendStorage.Resolution;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("registry")
class BrowserReadController {
  private final BrowserReads reads;

  BrowserReadController(BrowserReads reads) {
    this.reads = reads;
  }

  @GetMapping("/api/v1/sensors")
  BrowserReads.SensorPage sensors(
      @AuthenticationPrincipal BrowserSession.Principal principal,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String cursor,
      HttpServletRequest request) {
    if (limit < 1 || limit > 200) {
      throw BrowserProblems.bad("INVALID_LIMIT", "Page size must be between 1 and 200.");
    }
    return reads.sensors(
        principal, limit, cursor, request.getSession(false).getId(), Instant.now());
  }

  @GetMapping("/api/v1/trends")
  BrowserReads.TrendView trends(
      @AuthenticationPrincipal BrowserSession.Principal principal,
      @RequestParam String from,
      @RequestParam String to,
      @RequestParam String bucket,
      HttpServletRequest request) {
    var observed = Instant.now();
    Resolution resolution =
        switch (bucket) {
          case "15m" -> Resolution.QUARTER_HOUR;
          case "1h" -> Resolution.HOUR;
          case "1d" -> Resolution.DAY;
          case "1w" -> Resolution.WEEK;
          default -> throw BrowserProblems.bad("INVALID_BUCKET", "Choose 15m, 1h, 1d or 1w.");
        };
    Instant start;
    Instant end;
    try {
      start = Instant.parse(from);
      end = Instant.parse(to);
    } catch (DateTimeParseException invalid) {
      throw invalidRange();
    }
    if (!resolution.isAligned(start)
        || !resolution.isAligned(end)
        || !start.isBefore(end)
        || start.isBefore(observed.minus(Duration.ofDays(30)))
        || end.isAfter(resolution.endOf(observed))
        || Duration.between(start, end).compareTo(Duration.ofDays(30)) > 0) {
      throw invalidRange();
    }
    return reads.trend(
        principal,
        sensorIds(request.getParameterValues("sensorId")),
        start,
        end,
        bucket,
        resolution,
        observed);
  }

  private static BrowserProblems.Failure invalidRange() {
    return BrowserProblems.bad(
        "INVALID_RANGE", "Specify an aligned UTC range within the last 30 days.");
  }

  private static List<UUID> sensorIds(String[] values) {
    if (values == null || values.length < 1 || values.length > 10) {
      throw BrowserProblems.bad("INVALID_SENSORS", "Specify one to ten distinct sensor IDs.");
    }
    var ids = new ArrayList<UUID>();
    var unique = new HashSet<UUID>();
    try {
      for (String value : values) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value) || !unique.add(id)) {
          throw new IllegalArgumentException("Invalid or repeated sensor ID");
        }
        ids.add(id);
      }
    } catch (IllegalArgumentException invalid) {
      throw BrowserProblems.bad("INVALID_SENSORS", "Specify one to ten distinct sensor IDs.");
    }
    return ids;
  }
}
