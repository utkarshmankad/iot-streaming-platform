package iot.platform.backend.registry;

import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("registry")
public class OperatorTenantAuthorizer {
  private final JdbcClient jdbc;
  private final TenantScope scope;

  OperatorTenantAuthorizer(JdbcClient jdbc, TenantScope scope) {
    this.jdbc = jdbc;
    this.scope = scope;
  }

  // The operator ID comes from the authenticated operator principal, never from the request body.
  @Transactional(readOnly = true)
  public Optional<String> activeMembership(UUID authenticatedOperator, UUID requestedTenant) {
    scope.bind(requestedTenant);
    return jdbc.sql(
            """
        SELECT m.role
        FROM registry.operator_memberships m
        JOIN registry.tenants t USING (tenant_id)
        JOIN registry.operators o USING (operator_id)
        WHERE m.tenant_id = ? AND m.operator_id = ? AND t.status = 'active' AND o.status = 'active'
        """)
        .params(requestedTenant, authenticatedOperator)
        .query(String.class)
        .optional();
  }
}
