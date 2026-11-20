package iot.platform.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import iot.platform.control.proto.SamplingAcknowledgment;
import iot.platform.control.proto.SamplingCommand;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class CommandProtocolTest {
  private static final String COMMAND = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String SENSOR = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

  @Test
  void requiresExplicitSamplingStateAndValidCommandIdentity() {
    var command =
        SamplingCommand.newBuilder()
            .setSchemaVersion(1)
            .setCommandId(COMMAND)
            .setSensorId(SENSOR)
            .setVersion(1)
            .setEnabled(false)
            .setExpiresAtMs(1)
            .build();

    assertTrue(CommandProtocol.valid(command));
    assertTrue(CommandProtocol.valid(command.toBuilder().setEnabled(true).build()));
    assertFalse(CommandProtocol.valid(command.toBuilder().clearEnabled().build()));
    assertFalse(CommandProtocol.valid(command.toBuilder().setExpiresAtMs(0).build()));
    assertFalse(CommandProtocol.valid(command.toBuilder().setVersion(0).build()));
    assertFalse(CommandProtocol.valid(command.toBuilder().setSchemaVersion(2).build()));
    assertFalse(CommandProtocol.valid(command.toBuilder().setCommandId("invalid").build()));
    assertFalse(
        CommandProtocol.valid(
            command.toBuilder().setSensorId(SENSOR.toUpperCase(Locale.ROOT)).build()));
  }

  @Test
  void requiresExplicitAppliedStateAndValidAcknowledgmentTime() {
    var acknowledgment =
        SamplingAcknowledgment.newBuilder()
            .setSchemaVersion(1)
            .setCommandId(COMMAND)
            .setSensorId(SENSOR)
            .setVersion(1)
            .setEnabled(false)
            .setAppliedAtMs(1)
            .build();

    assertTrue(CommandProtocol.valid(acknowledgment));
    assertFalse(CommandProtocol.valid(acknowledgment.toBuilder().clearEnabled().build()));
    assertFalse(CommandProtocol.valid(acknowledgment.toBuilder().setAppliedAtMs(0).build()));
    assertFalse(CommandProtocol.valid(acknowledgment.toBuilder().setVersion(0).build()));
    assertFalse(CommandProtocol.valid(acknowledgment.toBuilder().setSchemaVersion(2).build()));
    assertFalse(CommandProtocol.valid(acknowledgment.toBuilder().setCommandId("invalid").build()));
    assertFalse(CommandProtocol.valid(acknowledgment.toBuilder().setSensorId("invalid").build()));
  }
}
