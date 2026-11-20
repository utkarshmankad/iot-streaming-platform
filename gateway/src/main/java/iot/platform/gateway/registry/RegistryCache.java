package iot.platform.gateway.registry;

import com.google.protobuf.ByteString;
import iot.platform.registry.proto.CachedRegistry;
import iot.platform.registry.proto.RegistrySnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RegistryCache {
  private static final int MAX_BYTES = 1024 * 1024;
  private static final Logger LOG = LoggerFactory.getLogger(RegistryCache.class);

  private final HttpClient http;
  private final URI endpoint;
  private final String token;
  private final UUID tenant;
  private final UUID gateway;
  private final Path file;
  private volatile Entry current;

  public RegistryCache(
      HttpClient http, URI endpoint, String token, UUID tenant, UUID gateway, Path file) {
    this.http = http;
    this.endpoint = endpoint;
    this.token = token;
    this.tenant = tenant;
    this.gateway = gateway;
    this.file = file.toAbsolutePath();
    if (Files.isRegularFile(this.file)) {
      try (InputStream stream = Files.newInputStream(this.file)) {
        var cached = CachedRegistry.parseFrom(readBounded(stream, MAX_BYTES + 100));
        current = validate(cached.getEtag(), cached.getBody());
      } catch (IOException invalid) {
        LOG.warn("Ignoring unverified registry cache for tenant {}", tenant);
      }
    }
  }

  public Optional<RegistrySnapshot> current() {
    return Optional.ofNullable(current).map(Entry::snapshot);
  }

  public UUID tenant() {
    return tenant;
  }

  public void authorize(HttpRequest.Builder request) {
    request.header("Authorization", "Bearer " + token);
  }

  public synchronized void refresh() throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/x-protobuf");
    authorize(request);
    if (current != null) {
      request.header("If-None-Match", current.etag());
    }
    var response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream stream = response.body()) {
      switch (response.statusCode()) {
        case 200 -> {
          var body = readBounded(stream, MAX_BYTES);
          String etag =
              response
                  .headers()
                  .firstValue("ETag")
                  .orElseThrow(() -> new IOException("Registry response is missing its version"));
          Entry verified = validate(etag, body);
          persist(CachedRegistry.newBuilder().setEtag(etag).setBody(body).build());
          current = verified;
        }
        case 304 -> {
          if (current == null) {
            throw new IOException("Registry returned not-modified without a verified snapshot");
          }
        }
        case 401, 403 -> {
          current = null;
          Files.deleteIfExists(file);
          throw new IOException("Registry authentication or assignment was rejected");
        }
        default ->
            throw new IOException("Registry request failed with status " + response.statusCode());
      }
    }
  }

  private void persist(CachedRegistry snapshot) throws IOException {
    Path parent = Objects.requireNonNull(file.getParent());
    Files.createDirectories(parent);
    Path pending = Files.createTempFile(parent, "snapshot-", ".tmp");
    try {
      Files.write(pending, snapshot.toByteArray());
      Files.move(
          pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(pending);
    }
  }

  private Entry validate(String etag, ByteString body) throws IOException {
    if (body.size() > MAX_BYTES || !etag.equals(fingerprint(body))) {
      throw new IOException("Registry snapshot version does not match its bytes");
    }
    var snapshot = RegistrySnapshot.parseFrom(body);
    if (snapshot.getFormatVersion() != 1
        || !tenant.toString().equals(snapshot.getTenantId())
        || !gateway.toString().equals(snapshot.getGatewayId())) {
      throw new IOException("Registry snapshot scope or format mismatch");
    }
    var devices = new HashSet<UUID>();
    var sensors = new HashSet<UUID>();
    try {
      for (var device : snapshot.getDevicesList()) {
        if (!devices.add(UUID.fromString(device.getDeviceId()))
            || device.getBrokerClientId().isBlank()) {
          throw new IOException("Invalid registry device");
        }
        for (var sensor : device.getSensorsList()) {
          if (!sensors.add(UUID.fromString(sensor.getSensorId()))
              || sensor.getTypeCode().isBlank()
              || sensor.getUnit().isBlank()
              || sensor.getSamplingPeriodMs() <= 0) {
            throw new IOException("Invalid registry sensor");
          }
          var versions = new HashSet<Integer>();
          for (var schema : sensor.getAcceptedSchemasList()) {
            if (schema.getVersion() <= 0
                || !versions.add(schema.getVersion())
                || schema.getProtobufContract().isBlank()
                || schema.getValidatorRevision().isBlank()
                || !Double.isFinite(schema.getMinValue())
                || !Double.isFinite(schema.getMaxValue())
                || schema.getMinValue() > schema.getMaxValue()) {
              throw new IOException("Invalid registry schema");
            }
          }
        }
      }
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid registry identifier", invalid);
    }
    return new Entry(etag, snapshot);
  }

  private static ByteString readBounded(InputStream stream, int limit) throws IOException {
    byte[] body = stream.readNBytes(limit + 1);
    if (body.length > limit) {
      throw new IOException("Registry snapshot exceeds the size limit");
    }
    return ByteString.copyFrom(body);
  }

  private static String fingerprint(ByteString body) {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      hash.update(body.asReadOnlyByteBuffer());
      return "\"" + HexFormat.of().formatHex(hash.digest()) + "\"";
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Required SHA-256 algorithm unavailable", impossible);
    }
  }

  private record Entry(String etag, RegistrySnapshot snapshot) {}
}
