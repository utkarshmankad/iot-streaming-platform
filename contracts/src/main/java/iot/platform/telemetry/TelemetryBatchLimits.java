package iot.platform.telemetry;

public final class TelemetryBatchLimits {
  public static final String PATH = "/internal/v1/telemetry/batches";
  public static final String CONTENT_TYPE = "application/x-protobuf";
  public static final int MAX_ENTRIES = 250;
  public static final int MAX_REQUEST_BYTES = 1_100_000;
  public static final int MAX_RESPONSE_BYTES = 128_000;

  private TelemetryBatchLimits() {}
}
