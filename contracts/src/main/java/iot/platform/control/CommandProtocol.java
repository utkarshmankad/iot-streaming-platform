package iot.platform.control;

import iot.platform.control.proto.SamplingAcknowledgment;
import iot.platform.control.proto.SamplingCommand;
import java.util.UUID;

public final class CommandProtocol {
  public static final int VERSION = 1;
  public static final int MAX_PENDING = 100;
  public static final int MAX_PACKET_BYTES = 4096;
  public static final int MAX_PAGE_BYTES = 128_000;
  public static final String CONTENT_TYPE = "application/x-protobuf";
  public static final String PATH = "/internal/v1/commands";

  private CommandProtocol() {}

  public static boolean valid(SamplingCommand value) {
    return identity(
            value.getSchemaVersion(), value.getCommandId(), value.getSensorId(), value.getVersion())
        && value.hasEnabled()
        && value.getExpiresAtMs() > 0;
  }

  public static boolean valid(SamplingAcknowledgment value) {
    return identity(
            value.getSchemaVersion(), value.getCommandId(), value.getSensorId(), value.getVersion())
        && value.hasEnabled()
        && value.getAppliedAtMs() > 0;
  }

  public static boolean canonicalUuid(String value) {
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static boolean identity(int schema, String command, String sensor, long version) {
    return schema == VERSION && version > 0 && canonicalUuid(command) && canonicalUuid(sensor);
  }
}
