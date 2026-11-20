package iot.platform.backend.ingest;

import iot.platform.backend.registry.GatewayAccess;
import iot.platform.telemetry.TelemetryBatchLimits;
import iot.platform.telemetry.proto.TelemetryBatch;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("registry")
class TelemetryController {
  private static final Logger LOG = LoggerFactory.getLogger(TelemetryController.class);
  private final TelemetryIngest ingest;

  TelemetryController(TelemetryIngest ingest) {
    this.ingest = ingest;
  }

  @PostMapping(
      value = TelemetryBatchLimits.PATH,
      consumes = TelemetryBatchLimits.CONTENT_TYPE,
      produces = TelemetryBatchLimits.CONTENT_TYPE)
  ResponseEntity<byte[]> batch(
      HttpServletRequest request,
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    long receivedMs = System.currentTimeMillis();
    String token = GatewayAccess.bearerToken(authorization);
    if (token == null) {
      return unauthorized();
    }

    TelemetryBatch batch;
    try {
      if (request.getContentLengthLong() > TelemetryBatchLimits.MAX_REQUEST_BYTES) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
      }
      byte[] bounded =
          request.getInputStream().readNBytes(TelemetryBatchLimits.MAX_REQUEST_BYTES + 1);
      if (bounded.length > TelemetryBatchLimits.MAX_REQUEST_BYTES) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
      }
      batch = TelemetryBatch.parseFrom(bounded);
    } catch (IOException malformed) {
      return ResponseEntity.badRequest().build();
    }
    if (batch.getEntriesCount() > TelemetryBatchLimits.MAX_ENTRIES) {
      return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
    }
    if (!validEnvelope(batch)) {
      return ResponseEntity.badRequest().build();
    }

    return ingest
        .ingest(token, batch, receivedMs)
        .map(
            response ->
                ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(TelemetryBatchLimits.CONTENT_TYPE))
                    .body(response.toByteArray()))
        .orElseGet(TelemetryController::unauthorized);
  }

  private static boolean validEnvelope(TelemetryBatch batch) {
    if (batch.getEntriesCount() == 0) {
      return false;
    }
    var correlations = new HashSet<String>();
    for (var entry : batch.getEntriesList()) {
      String id = entry.getCorrelationId();
      if (id.isBlank() || id.length() > 64 || !correlations.add(id)) {
        return false;
      }
    }
    return true;
  }

  private static ResponseEntity<byte[]> unauthorized() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        .build();
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  ResponseEntity<byte[]> storageUnavailable(RuntimeException failure) {
    LOG.warn("Telemetry batch could not commit ({})", failure.getClass().getSimpleName());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, "1")
        .build();
  }
}
