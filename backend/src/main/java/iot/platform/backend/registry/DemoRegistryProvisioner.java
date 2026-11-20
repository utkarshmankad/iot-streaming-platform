package iot.platform.backend.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("registry-provision")
final class DemoRegistryProvisioner implements ApplicationRunner {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final String tokenA;
  private final String tokenB;
  private final Path acl;

  DemoRegistryProvisioner(
      JdbcTemplate jdbc,
      TransactionTemplate transaction,
      @Value("${IOT_REGISTRY_GATEWAY_A_TOKEN}") String tokenA,
      @Value("${IOT_REGISTRY_GATEWAY_B_TOKEN}") String tokenB,
      @Value("${IOT_REGISTRY_ACL:/var/lib/iot/registry.acl}") Path acl) {
    if (!GatewayToken.valid(tokenA) || !GatewayToken.valid(tokenB) || tokenA.equals(tokenB)) {
      throw new IllegalArgumentException(
          "Distinct generated registry gateway credentials are required");
    }
    this.jdbc = jdbc;
    this.transaction = transaction;
    this.tokenA = tokenA;
    this.tokenB = tokenB;
    this.acl = acl;
  }

  @Override
  public void run(ApplicationArguments ignored) throws Exception {
    String projection = Objects.requireNonNull(transaction.execute(status -> provision()));
    Path file = acl.toAbsolutePath();
    Path parent = Objects.requireNonNull(file.getParent());
    Files.createDirectories(parent);
    // The provisioner and broker share only the ACL file group.
    Path pending =
        Files.createTempFile(
            parent,
            "registry-acl-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r-----")));
    try {
      Files.writeString(pending, projection);
      Files.move(
          pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(pending);
    }
  }

  private String provision() {
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              new ResourceDatabasePopulator(new ClassPathResource("db/demo/registry.sql"))
                  .populate(connection);
              return null;
            });
    storeToken("gateway-a", tokenA);
    storeToken("gateway-b", tokenB);
    return brokerAcl();
  }

  private void storeToken(String principal, String token) {
    jdbc.update(
        """
        INSERT INTO registry.gateway_credentials (token_digest, tenant_id, gateway_id)
        SELECT ?, tenant_id, gateway_id FROM registry.gateways WHERE credential_ref = ?
        ON CONFLICT (tenant_id, gateway_id) DO UPDATE SET token_digest = EXCLUDED.token_digest
        """,
        GatewayToken.digest(token),
        principal);
  }

  private String brokerAcl() {
    var principals = new TreeMap<String, List<String>>();
    principals.put("scaffold_health", List.of("topic read iot/scaffold/health"));
    jdbc.query(
        """
        SELECT g.tenant_id, g.credential_ref, d.device_id, d.mqtt_principal_ref, s.sensor_id
        FROM registry.gateways g
        JOIN registry.tenants t USING (tenant_id)
        JOIN registry.devices d ON d.tenant_id = g.tenant_id AND d.gateway_id = g.gateway_id
        JOIN registry.sensors s ON s.tenant_id = d.tenant_id AND s.device_id = d.device_id
        WHERE t.status = 'active' AND g.status = 'active' AND d.status = 'active'
          AND s.status = 'active'
        ORDER BY g.tenant_id, g.gateway_id, d.device_id, s.sensor_id
        """,
        row -> {
          String root =
              "iot/v1/"
                  + row.getString("tenant_id")
                  + "/"
                  + row.getString("device_id")
                  + "/"
                  + row.getString("sensor_id")
                  + "/";
          var gateway =
              principals.computeIfAbsent(row.getString("credential_ref"), key -> new ArrayList<>());
          var device =
              principals.computeIfAbsent(
                  row.getString("mqtt_principal_ref"), key -> new ArrayList<>());
          for (String kind : List.of("telemetry", "heartbeat", "command-ack")) {
            gateway.add("topic read " + root + kind);
            device.add("topic write " + root + kind);
          }
          for (String kind : List.of("receipt", "command")) {
            gateway.add("topic write " + root + kind);
            device.add("topic read " + root + kind);
          }
        });
    var text = new StringBuilder();
    principals.forEach(
        (name, rules) -> {
          text.append("user ").append(name).append('\n');
          rules.forEach(rule -> text.append(rule).append('\n'));
          text.append('\n');
        });
    return text.toString();
  }
}
