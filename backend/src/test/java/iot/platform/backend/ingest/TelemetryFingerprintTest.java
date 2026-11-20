package iot.platform.backend.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import iot.platform.telemetry.proto.TelemetryEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TelemetryFingerprintTest {
  @Test
  void canonicalizesSignedZeroAndPreservesTimestampAndReadingContent() {
    var key = new TelemetryStore.Key(new UUID(0, 2), new UUID(0, 3));
    var event = TelemetryEvent.newBuilder().setSchemaVersion(1).setObservedAtMs(1000);
    var first = TelemetryIngest.fingerprint(key, event.setValue(0.0).build());
    assertEquals(first, TelemetryIngest.fingerprint(key, event.setValue(-0.0).build()));
    assertNotEquals(first, TelemetryIngest.fingerprint(key, event.setValue(1.0).build()));
    assertNotEquals(
        first, TelemetryIngest.fingerprint(key, event.setValue(0.0).setObservedAtMs(1001).build()));
  }
}
