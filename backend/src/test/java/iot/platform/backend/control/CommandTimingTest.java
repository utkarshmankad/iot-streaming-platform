package iot.platform.backend.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandTimingTest {
  private static final Instant ISSUED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant EXPIRES = ISSUED.plusSeconds(60);

  @Test
  void expiresPendingAtTheDeadline() {
    var command = command("pending");
    assertEquals("pending", command.statusAt(EXPIRES.minusMillis(1)));
    assertEquals("expired", command.statusAt(EXPIRES));
    assertEquals("expired", command.statusAt(EXPIRES.plusMillis(1)));
  }

  @Test
  void preservesTerminalOutcomesAcrossTheDeadline() {
    for (String status : List.of("applied", "failed", "superseded", "expired")) {
      var command = command(status);
      assertEquals(status, command.statusAt(EXPIRES.minusMillis(1)));
      assertEquals(status, command.statusAt(EXPIRES.plusMillis(1)));
    }
  }

  @Test
  void exposesTheReasonForEachUnsuccessfulTerminalOutcome() {
    assertEquals("NEWER_COMMAND", CommandStore.Command.reason("superseded"));
    assertEquals("COMMAND_EXPIRED", CommandStore.Command.reason("expired"));
    assertEquals("STATE_MISMATCH", CommandStore.Command.reason("failed"));
    assertNull(CommandStore.Command.reason("pending"));
    assertNull(CommandStore.Command.reason("applied"));
  }

  private static CommandStore.Command command(String state) {
    return new CommandStore.Command(
        new UUID(0, 1),
        new UUID(0, 2),
        new UUID(0, 3),
        1,
        false,
        state,
        ISSUED,
        EXPIRES,
        null,
        null,
        null);
  }
}
