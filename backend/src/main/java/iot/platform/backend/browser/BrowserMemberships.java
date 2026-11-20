package iot.platform.backend.browser;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Profile("registry")
class BrowserMemberships {
  private final JdbcClient jdbc;

  BrowserMemberships(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  List<Membership> forLogin(String username) {
    return jdbc.sql(
            """
        SELECT operator_id, tenant_id, display_name, role FROM registry.browser_memberships(?)
        """)
        .param(username)
        .query(
            (row, index) ->
                new Membership(
                    row.getObject("operator_id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                    row.getString("display_name"), row.getString("role")))
        .list();
  }

  record Membership(UUID operatorId, UUID tenantId, String displayName, String role) {}
}
