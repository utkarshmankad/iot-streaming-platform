package iot.platform.backend.registry;

import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("registry")
public class GatewayAccess {
  private final JdbcClient jdbc;
  private final TenantScope scope;

  GatewayAccess(JdbcClient jdbc, TenantScope scope) {
    this.jdbc = jdbc;
    this.scope = scope;
  }

  public static String bearerToken(String authorization) {
    String prefix = "Bearer ";
    return authorization != null && authorization.regionMatches(true, 0, prefix, 0, prefix.length())
        ? authorization.substring(prefix.length())
        : null;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Assignment> authorize(String token) {
    if (!GatewayToken.valid(token)) {
      return Optional.empty();
    }
    var assignment =
        jdbc.sql(
                """
        SELECT tenant_id, gateway_id FROM registry.gateway_credentials WHERE token_digest = ?
        """)
            .param(GatewayToken.digest(token))
            .query(
                (row, number) ->
                    new Assignment(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("gateway_id", UUID.class)))
            .optional();
    return assignment.filter(this::active);
  }

  private boolean active(Assignment assignment) {
    scope.bind(assignment.tenant());
    return jdbc.sql(
            """
        SELECT EXISTS (
          SELECT FROM registry.gateways g JOIN registry.tenants t USING (tenant_id)
          WHERE g.tenant_id = ? AND g.gateway_id = ? AND g.status = 'active' AND t.status = 'active'
        )
        """)
        .params(assignment.tenant(), assignment.gateway())
        .query(Boolean.class)
        .single();
  }

  public record Assignment(UUID tenant, UUID gateway) {}
}
