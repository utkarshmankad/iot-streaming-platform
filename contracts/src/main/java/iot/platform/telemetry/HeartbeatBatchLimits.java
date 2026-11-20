package iot.platform.telemetry;

public final class HeartbeatBatchLimits {
  public static final String PATH = "/internal/v1/sensor-heartbeats";
  public static final int MAX_OBSERVATIONS = 250;
  public static final int MAX_BYTES = 64_000;
  public static final long OFFLINE_MS = 15_000;
  public static final long FUTURE_MS = 120_000;

  private HeartbeatBatchLimits() {}
}
