package iot.platform.backend.browser;

import com.fasterxml.jackson.annotation.JsonInclude;
import iot.platform.backend.control.CommandStore;
import iot.platform.backend.control.CommandStore.Command;
import iot.platform.backend.registry.OperatorTenantAuthorizer;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("registry")
class BrowserCommands {
  private final CommandStore store;
  private final OperatorTenantAuthorizer memberships;
  private final long ttl;
  private final boolean restoreHold;

  BrowserCommands(
      CommandStore store,
      OperatorTenantAuthorizer memberships,
      @Value("${IOT_COMMAND_TTL_MS:60000}") long ttl,
      @Value("${IOT_CONTROL_RESTORE_HOLD:false}") boolean restoreHold) {
    this.store = store;
    this.memberships = memberships;
    this.ttl = Math.clamp(ttl, 1_000, 60_000);
    this.restoreHold = restoreHold;
  }

  @PostMapping(value = "/api/v1/sensors/{sensor}/commands", consumes = "application/json")
  @Transactional(timeout = 5)
  public ResponseEntity<Accepted> create(
      @AuthenticationPrincipal BrowserSession.Principal principal,
      @PathVariable UUID sensor,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader(value = "X-IoT-Tenant", required = false) String expectedTenant,
      @RequestBody Request request) {
    if (expectedTenant != null && !principal.tenantId().toString().equals(expectedTenant)) {
      throw new BrowserProblems.Failure(
          HttpStatus.CONFLICT, "SESSION_CHANGED", "The browser session changed.");
    }
    if (!"operator".equals(role(principal))) {
      throw BrowserProblems.forbidden();
    }
    if (restoreHold) {
      throw new BrowserProblems.Failure(
          HttpStatus.SERVICE_UNAVAILABLE,
          "RECOVERY_HOLD",
          "Sensor controls are held during recovery.");
    }
    if (request.enabled() == null || !key.matches("[!-~]{1,128}")) {
      throw BrowserProblems.bad(
          "INVALID_COMMAND", "Provide a sampling state and a valid idempotency key.");
    }
    var command =
        store
            .create(
                principal.tenantId(), principal.operatorId(), sensor, key, request.enabled(), ttl)
            .orElseThrow(BrowserProblems::missing);
    if (command.requestedEnabled() != request.enabled()) {
      throw new BrowserProblems.Failure(
          HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "This key has a different requested state.");
    }
    var response =
        new Accepted(
            command.commandId(),
            command.sensorId(),
            command.version(),
            command.requestedEnabled(),
            "pending",
            command.createdAt(),
            command.expiresAt());
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/commands/" + command.commandId()))
        .body(response);
  }

  @GetMapping("/api/v1/commands/{id}")
  @Transactional(timeout = 5)
  public Status find(
      @AuthenticationPrincipal BrowserSession.Principal principal, @PathVariable UUID id) {
    role(principal);
    Instant now = Instant.now();
    var command =
        store
            .expireAndRead(principal.tenantId(), id, now)
            .orElseThrow(
                () ->
                    new BrowserProblems.Failure(
                        HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "The command is unavailable."));
    String status = command.statusAt(now);
    var resolved = command.resolvedAt();
    if (resolved == null && "expired".equals(status)) {
      resolved = command.expiresAt();
    }
    return new Status(
        command.commandId(),
        command.sensorId(),
        command.version(),
        command.requestedEnabled(),
        status,
        command.createdAt(),
        command.expiresAt(),
        command.actualEnabled(),
        resolved,
        Command.reason(status));
  }

  private String role(BrowserSession.Principal principal) {
    return memberships
        .activeMembership(principal.operatorId(), principal.tenantId())
        .orElseThrow(BrowserProblems::forbidden);
  }

  record Request(Boolean enabled) {}

  record Accepted(
      UUID commandId,
      UUID sensorId,
      long version,
      boolean requestedEnabled,
      String status,
      Instant createdAt,
      Instant expiresAt) {}

  record Status(
      UUID commandId,
      UUID sensorId,
      long version,
      boolean requestedEnabled,
      String status,
      Instant createdAt,
      Instant expiresAt,
      Boolean confirmedEnabled,
      Instant resolvedAt,
      @JsonInclude(JsonInclude.Include.NON_NULL) String reasonCode) {}
}
