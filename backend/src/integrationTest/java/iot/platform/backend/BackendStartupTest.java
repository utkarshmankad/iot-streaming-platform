package iot.platform.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class BackendStartupTest {
  @Test
  void loadsBackendConfiguration() {
    try (var context =
        new SpringApplicationBuilder(BackendApplication.class).web(WebApplicationType.NONE).run()) {
      assertEquals("iot-backend", context.getEnvironment().getProperty("spring.application.name"));
    }
  }
}
