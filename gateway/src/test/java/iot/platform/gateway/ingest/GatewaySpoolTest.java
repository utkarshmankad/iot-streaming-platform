package iot.platform.gateway.ingest;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import iot.platform.telemetry.proto.TelemetryReceipt.Outcome;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewaySpoolTest {
  static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  static final String DEVICE = "00000000-0000-4000-8000-000000000002";
  static final String SENSOR = "00000000-0000-4000-8000-000000000006";
  private static final long NOW = 1_800_000_000_000L;
  @TempDir Path directory;

  @Test
  void retainsTenantScopedAcknowledgmentsAcrossRestart() throws Exception {
    var file = directory.resolve("command-spool.sqlite");
    var shared = UUID.randomUUID().toString();
    try (var spool = GatewaySpool.open(file, 10_000)) {
      spool.queueAck(TENANT_A, shared, new byte[] {1});
      spool.queueAck(TENANT_A, shared, new byte[] {9});
      spool.queueAck(TENANT_B, shared, new byte[] {2});
    }
    try (var spool = GatewaySpool.open(file, 10_000)) {
      assertArrayEquals(new byte[] {1}, spool.nextAck(TENANT_A).payload());
      assertArrayEquals(new byte[] {2}, spool.nextAck(TENANT_B).payload());
      spool.finishAck(TENANT_A, shared);
      assertNull(spool.nextAck(TENANT_A));
      assertEquals(shared, spool.nextAck(TENANT_B).id());
    }
  }

  @Test
  void admitsAtomicallyByTenantAndRecognizesDuplicateConflictingAndFullEvents() throws Exception {
    var file = directory.resolve("volume/spool.sqlite");
    var original = reading(1, "original", NOW);
    var modified = reading(1, "modified", NOW);
    var next = reading(2, "second", NOW);
    assertThrows(IllegalArgumentException.class, () -> GatewaySpool.open(file, 0));
    try (var spool = GatewaySpool.open(file, 10)) {
      assertEquals(
          List.of(Outcome.ACCEPTED, Outcome.DUPLICATE, Outcome.REJECTED, Outcome.RETRYABLE),
          spool.admit(TENANT_A, List.of(original, original, modified, next)).stream()
              .map(GatewaySpool.Admission::outcome)
              .toList());
      assertEquals(Outcome.ACCEPTED, spool.admit(TENANT_B, List.of(modified)).getFirst().outcome());
      spool.invalid(TENANT_B, "invalid-reading");
    }
    try (var spool = GatewaySpool.open(file, 10)) {
      var a = spool.stats(TENANT_A);
      assertEquals(new GatewaySpool.Stats(1, 8, 1, 8, 1, 0, 1, 1, NOW), a);
      var b = spool.stats(TENANT_B);
      assertEquals(new GatewaySpool.Stats(1, 8, 1, 8, 0, 0, 0, 1, NOW), b);
      var event = spool.next(TENANT_A, NOW, 250, false).getFirst();
      assertEquals(original.payload(), event.payload());
      spool.finish(TENANT_B, List.of(new GatewaySpool.Delivered(event.id(), "")));
      assertEquals(b, spool.stats(TENANT_B));
      spool.finish(TENANT_A, List.of(new GatewaySpool.Delivered(event.id(), "retention-expired")));
      assertEquals(new GatewaySpool.Stats(0, 0, 1, 8, 1, 0, 1, 2, 0), spool.stats(TENANT_A));
      assertEquals(Outcome.ACCEPTED, spool.admit(TENANT_A, List.of(next)).getFirst().outcome());
    }
    try (var sql = DriverManager.getConnection("jdbc:sqlite:" + file);
        var statement = sql.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT stage, reason, count FROM rejection_totals ORDER BY stage, reason")) {
      assertTrue(rows.next());
      assertEquals("backend", rows.getString("stage"));
      assertEquals("retention-expired", rows.getString("reason"));
      assertTrue(rows.next());
      assertEquals("event-conflict", rows.getString("reason"));
      assertTrue(rows.next());
      assertEquals("invalid-reading", rows.getString("reason"));
      assertEquals(1, rows.getInt("count"));
      assertFalse(rows.next());
    }
  }

  @Test
  void reserveCapacityForLiveAndReplayAndPersistEligibilityAcrossRestart() throws Exception {
    var file = directory.resolve("spool.sqlite");
    try (var spool = GatewaySpool.open(file, 1000)) {
      assertEquals(0, spool.stats(TENANT_A).events());
      spool.admit(
          TENANT_A,
          List.of(
              reading(1, "old-a", NOW - 10000), reading(2, "old-b", NOW - 9000),
              reading(3, "live-a", NOW - 10), reading(4, "live-b", NOW - 9)));
      var first = spool.next(TENANT_A, NOW, 2, false);
      assertEquals(
          List.of("live-a", "old-a"), first.stream().map(x -> x.payload().toStringUtf8()).toList());
      assertEquals(
          "live-a", spool.next(TENANT_A, NOW, 1, false).getFirst().payload().toStringUtf8());
      assertEquals("old-a", spool.next(TENANT_A, NOW, 1, true).getFirst().payload().toStringUtf8());
      spool.retry(TENANT_A, first, NOW);
      assertTrue(spool.next(TENANT_B, NOW, 250, false).isEmpty());
      assertEquals(2, spool.next(TENANT_A, NOW, 250, false).size());
    }
    try (var spool = GatewaySpool.open(file, 1000)) {
      var eligible = spool.next(TENANT_A, NOW + 2000, 250, false);
      assertEquals(4, eligible.size());
      assertEquals(2, eligible.stream().filter(row -> row.attempts() == 1).count());
      spool.finish(
          TENANT_A,
          eligible.stream().map(row -> new GatewaySpool.Delivered(row.id(), "")).toList());
      assertEquals(0, spool.stats(TENANT_A).bytes());
      assertEquals(4, spool.stats(TENANT_A).delivered());
    }
  }

  static GatewaySpool.Reading reading(int id, String payload, long received) {
    var raw = ByteString.copyFromUtf8(payload);
    return new GatewaySpool.Reading(DEVICE, SENSOR, new UUID(0, id).toString(), raw, raw, received);
  }
}
