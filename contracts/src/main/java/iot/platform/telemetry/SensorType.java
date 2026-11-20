package iot.platform.telemetry;

import java.util.Arrays;

public enum SensorType {
  TEMPERATURE("temperature", -100, 100),
  HUMIDITY("humidity", 0, 100),
  PRESSURE("pressure", 0, 2000),
  PM25("pm25", 0, 100000),
  LIGHT("light", 0, 1000000),
  OXYGEN("oxygen", 0, 100);

  private final String code;
  private final double minimum;
  private final double maximum;

  SensorType(String code, double minimum, double maximum) {
    this.code = code;
    this.minimum = minimum;
    this.maximum = maximum;
  }

  public static SensorType fromCode(String code) {
    return Arrays.stream(values())
        .filter(type -> type.code.equals(code))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unsupported sensor type"));
  }

  public boolean accepts(double value) {
    return Double.isFinite(value) && value >= minimum && value <= maximum;
  }
}
