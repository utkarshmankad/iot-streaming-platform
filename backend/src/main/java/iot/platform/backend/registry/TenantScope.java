package iot.platform.backend.registry;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@Profile("registry")
class TenantScope {
  private final JdbcClient jdbc;

  TenantScope(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void bind(UUID tenantId) {
    jdbc.sql("SELECT set_config('iot.tenant_id', ?, true)")
        .param(tenantId.toString())
        .query(String.class)
        .single();
  }
}
