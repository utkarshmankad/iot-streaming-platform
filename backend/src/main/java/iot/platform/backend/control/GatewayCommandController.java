package iot.platform.backend.control;

import com.google.protobuf.InvalidProtocolBufferException;
import iot.platform.backend.registry.GatewayAccess;
import iot.platform.control.CommandProtocol;
import iot.platform.control.proto.SamplingAcknowledgment;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("registry")
@RequestMapping(CommandProtocol.PATH)
class GatewayCommandController {
  private final GatewayAccess access;
  private final CommandStore store;
  private final boolean restoreHold;

  GatewayCommandController(
      GatewayAccess access,
      CommandStore store,
      @Value("${IOT_CONTROL_RESTORE_HOLD:false}") boolean restoreHold) {
    this.access = access;
    this.store = store;
    this.restoreHold = restoreHold;
  }

  @GetMapping(value = "/pending", produces = CommandProtocol.CONTENT_TYPE)
  @Transactional(timeout = 5)
  public ResponseEntity<byte[]> pending(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String header) {
    if (restoreHold) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    return access
        .authorize(GatewayAccess.bearerToken(header))
        .map(
            assignment -> ResponseEntity.ok(store.pending(assignment, Instant.now()).toByteArray()))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
  }

  @PostMapping(value = "/{id}/ack", consumes = CommandProtocol.CONTENT_TYPE)
  @Transactional(timeout = 5)
  public ResponseEntity<Void> acknowledge(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String header,
      @PathVariable UUID id,
      HttpServletRequest request)
      throws IOException {
    if (restoreHold) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    var assignment = access.authorize(GatewayAccess.bearerToken(header));
    if (assignment.isEmpty()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    byte[] payload = request.getInputStream().readNBytes(CommandProtocol.MAX_PACKET_BYTES + 1);
    Instant received = Instant.now();
    if (payload.length > CommandProtocol.MAX_PACKET_BYTES) {
      return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
    }
    SamplingAcknowledgment ack;
    try {
      ack = SamplingAcknowledgment.parseFrom(payload);
    } catch (InvalidProtocolBufferException malformed) {
      return ResponseEntity.badRequest().build();
    }
    if (!CommandProtocol.valid(ack) || !id.toString().equals(ack.getCommandId())) {
      return ResponseEntity.badRequest().build();
    }
    return switch (store.acknowledge(assignment.get(), ack, received)) {
      case ACCEPTED -> ResponseEntity.noContent().build();
      case MISSING -> ResponseEntity.notFound().build();
      case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).build();
    };
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  ResponseEntity<Void> unavailable() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
  }
}
