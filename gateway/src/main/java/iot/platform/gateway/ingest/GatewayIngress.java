package iot.platform.gateway.ingest;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import iot.platform.gateway.registry.RegistryCache;
import iot.platform.telemetry.TelemetryValidation;
import iot.platform.telemetry.proto.TelemetryEvent;
import iot.platform.telemetry.proto.TelemetryReceipt;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GatewayIngress {
  private static final Logger LOG = LoggerFactory.getLogger(GatewayIngress.class);
  private static final String EVENT_UUID =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private final GatewaySpool spool;
  private final RegistryCache registry;
  private final GatewayMqtt mqtt;
  private final IngressRegistry assignments = new IngressRegistry();
  private final ReceiptFault receiptFault;

  GatewayIngress(
      GatewaySpool spool, RegistryCache registry, GatewayMqtt mqtt, boolean injectFault) {
    this.spool = spool;
    this.registry = registry;
    this.mqtt = mqtt;
    receiptFault = injectFault ? new ReceiptFault() : null;
  }

  void tick(long now) throws SQLException {
    mqtt.advance(now);
    List<Pending> pending = new ArrayList<>();
    var snapshot = registry.current().orElse(null);
    for (int index = 0; index < 64; index++) {
      var incoming = mqtt.poll();
      if (incoming == null) {
        break;
      }
      var authorized = assignments.resolve(snapshot, incoming.topic());
      if (authorized == null) {
        // When no verified registry is available the producer must be able to retry later.
        if (snapshot != null) {
          spool.invalid(registry.tenant(), "unassigned-topic");
        }
        continue;
      }
      var valid = validate(incoming, authorized);
      if (valid != null) {
        pending.add(valid);
      }
    }
    if (pending.isEmpty()) {
      return;
    }
    List<GatewaySpool.Admission> admissions;
    try {
      admissions = spool.admit(registry.tenant(), pending.stream().map(Pending::reading).toList());
    } catch (SQLException unavailable) {
      LOG.warn(
          "Gateway spool write failed for tenant {} with SQLite error code {} (cleanup codes {})",
          registry.tenant(),
          unavailable.getErrorCode(),
          GatewaySpool.cleanupErrorCodes(unavailable));
      admissions =
          Collections.nCopies(
              pending.size(),
              new GatewaySpool.Admission(TelemetryReceipt.Outcome.RETRYABLE, "spool-unavailable"));
    }
    for (int index = 0; index < pending.size(); index++) {
      var delivery = pending.get(index);
      var outcome = admissions.get(index);
      send(
          delivery.topic(),
          delivery.reading().eventId(),
          delivery.reading().fingerprint(),
          outcome.outcome(),
          outcome.reason());
    }
  }

  private Pending validate(GatewayMqtt.Incoming inbound, IngressRegistry.Assignment assignment)
      throws SQLException {
    byte[] raw = inbound.payload();
    if (raw.length > TelemetryValidation.MAX_PAYLOAD_BYTES) {
      spool.invalid(registry.tenant(), "payload-too-large");
      return null;
    }
    TelemetryEvent event;
    try {
      event = TelemetryEvent.parseFrom(raw);
    } catch (InvalidProtocolBufferException malformed) {
      spool.invalid(registry.tenant(), "malformed-protobuf");
      return null;
    }
    var hash = fingerprint(raw);
    try {
      TelemetryValidation.validate(
          event, assignment.sensor(), assignment.type(), inbound.receivedMs());
      if (event.getValue() < assignment.minimum() || event.getValue() > assignment.maximum()) {
        throw new IllegalArgumentException("registry-reading-range");
      }
    } catch (IllegalArgumentException invalid) {
      spool.invalid(registry.tenant(), invalid.getMessage());
      if (event.getEventId().matches(EVENT_UUID)) {
        send(
            inbound.topic(),
            event.getEventId(),
            hash,
            TelemetryReceipt.Outcome.REJECTED,
            invalid.getMessage());
      }
      return null;
    }
    return new Pending(
        inbound.topic(),
        new GatewaySpool.Reading(
            assignment.device(),
            assignment.sensor().toString(),
            event.getEventId(),
            ByteString.copyFrom(raw),
            hash,
            inbound.receivedMs()));
  }

  private void send(
      String topic,
      String event,
      ByteString hash,
      TelemetryReceipt.Outcome outcome,
      String reason) {
    var receipt =
        TelemetryReceipt.newBuilder()
            .setSchemaVersion(TelemetryValidation.VERSION)
            .setEventId(event)
            .setFingerprint(hash)
            .setOutcome(outcome)
            .setReasonCode(reason)
            .build();
    if (receiptFault == null || receiptFault.mayPublish(topic, receipt)) {
      mqtt.receipt(topic, receipt);
    }
  }

  /** Enabled only in the disposable, explicitly activated fault-test profile. */
  private static final class ReceiptFault {
    private TelemetryReceipt withheld;
    private String withheldTopic;
    private boolean replaySeen;

    boolean mayPublish(String topic, TelemetryReceipt receipt) {
      if (withheld == null && receipt.getOutcome() == TelemetryReceipt.Outcome.ACCEPTED) {
        withheld = receipt;
        withheldTopic = topic;
        LOG.info("Fault fixture withheld application receipt {} {}", topic, receipt.getEventId());
        return false;
      }
      if (withheld != null
          && !replaySeen
          && topic.equals(withheldTopic)
          && receipt.getEventId().equals(withheld.getEventId())
          && receipt.getFingerprint().equals(withheld.getFingerprint())
          && (receipt.getOutcome() == TelemetryReceipt.Outcome.ACCEPTED
              || receipt.getOutcome() == TelemetryReceipt.Outcome.DUPLICATE)) {
        replaySeen = true;
        LOG.info("Fault fixture observed unchanged replay {} {}", topic, receipt.getEventId());
      }
      return true;
    }
  }

  private static ByteString fingerprint(byte[] bytes) {
    try {
      return ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is required", unavailable);
    }
  }

  private record Pending(String topic, GatewaySpool.Reading reading) {}
}
